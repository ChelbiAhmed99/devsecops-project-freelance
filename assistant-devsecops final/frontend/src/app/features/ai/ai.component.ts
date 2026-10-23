import { AfterViewInit, Component, DestroyRef, ElementRef, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AiChatStore, AiTab } from '../../core/ai-chat.store';
import { ApiService, errorMessage } from '../../core/api.service';
import { EventsService } from '../../core/events.service';
import { AuthService } from '../../core/auth.service';
import { AiRecommendation, FixStatus, JiraAction, JiraStatus, ValidationStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { CorrectionStepperComponent } from '../../shared/correction-stepper.component';
import { SHARED_PIPES } from '../../shared/pipes';

/** Ordre d'affichage dans la vue « Toutes » : ce qui attend une action d'abord. */
const STATUS_ORDER: Record<ValidationStatus, number> = { PENDING_REVIEW: 0, APPROVED: 1, REJECTED: 2 };

@Component({
  selector: 'app-ai',
  standalone: true,
  imports: [FormsModule, CorrectionStepperComponent, ...SHARED_PIPES],
  templateUrl: './ai.component.html',
})
export class AiComponent implements OnInit, AfterViewInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly events = inject(EventsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly chatLog = viewChild<ElementRef<HTMLDivElement>>('chatLog');
  readonly auth = inject(AuthService);

  /** Conversation, onglet et filtre conservés entre les écrans et les sessions. */
  readonly store = inject(AiChatStore);
  readonly tab = this.store.tab;
  readonly statusFilter = this.store.statusFilter;
  readonly messages = this.store.messages;
  readonly thinking = this.store.thinking;

  readonly recommendations = signal<AiRecommendation[]>([]);
  readonly loading = signal(false);
  readonly comments: Record<string, string> = {};
  readonly health = signal<{ status: string; provider?: string; model?: string } | null>(null);

  // --- Suivi des corrections (validées -> appliquées -> vérifiées)
  readonly fixFilter = signal<FixStatus | 'ALL'>('ALL');
  readonly references: Record<string, string> = {};
  readonly corrections = computed(() => this.recommendations()
    .filter((r) => r.status === 'APPROVED')
    .filter((r) => this.fixFilter() === 'ALL' || (r.fixStatus ?? 'NONE') === this.fixFilter())
    .sort((a, b) => new Date(b.reviewedAt ?? b.createdAt).getTime() - new Date(a.reviewedAt ?? a.createdAt).getTime()));
  readonly fixCounts = computed(() => {
    const approved = this.recommendations().filter((r) => r.status === 'APPROVED');
    const count = (s: FixStatus) => approved.filter((r) => (r.fixStatus ?? 'NONE') === s).length;
    return { all: approved.length, none: count('NONE'), applied: count('APPLIED') + count('NOT_ENOUGH_DATA'),
      verified: count('VERIFIED'), still: count('STILL_PRESENT') };
  });

  // --- Actions Jira (proposées -> approuvées / rejetées)
  readonly jiraActions = signal<JiraAction[]>([]);
  readonly jiraStatus = signal<JiraStatus | null>(null);
  readonly jiraFilter = signal<string>('PROPOSED');
  readonly jiraComments: Record<number, string> = {};
  readonly jiraOpen = signal<number | null>(null);
  readonly filteredJira = computed(() => this.jiraActions().filter((a) => !this.jiraFilter() || a.status === this.jiraFilter()));
  readonly pendingJira = computed(() => this.jiraActions().filter((a) => a.status === 'PROPOSED').length);

  readonly suggestions = [
    'Quelles sont les priorités à traiter aujourd\'hui ?',
    'Quelles vulnérabilités critiques dois-je corriger en premier ?',
    'Pourquoi des tests échouent-ils ?',
    'Quels services produisent le plus d\'anomalies ?',
  ];

  get question(): string {
    return this.store.draft;
  }
  set question(v: string) {
    this.store.draft = v;
  }

  readonly filtered = computed(() => {
    const f = this.statusFilter();
    const list = f ? this.recommendations().filter((r) => r.status === f) : [...this.recommendations()];
    return list.sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status]
      || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  });
  readonly counts = computed(() => {
    const list = this.recommendations();
    return {
      all: list.length,
      pending: list.filter((r) => r.status === 'PENDING_REVIEW').length,
      approved: list.filter((r) => r.status === 'APPROVED').length,
      rejected: list.filter((r) => r.status === 'REJECTED').length,
    };
  });

  constructor() {
    // Fait défiler le chat jusqu'au dernier message à chaque nouveau message.
    effect(() => {
      this.messages();
      this.thinking();
      this.scrollChat();
    });
  }

  ngOnInit(): void {
    this.loadRecommendations();
    this.api.aiHealth().subscribe({ next: (h) => this.health.set(h), error: () => this.health.set({ status: 'DOWN' }) });
    if (this.tab() === 'jira') this.loadJira();
    // Rafraîchit la liste dès qu'un traitement se termine côté serveur (nouvelles fiches possibles).
    this.events.events$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((e) => {
      if (e.type !== 'logs.ingested') this.loadRecommendations(true);
      if (e.type.startsWith('jira.') && this.tab() === 'jira') this.loadJira();
    });
  }

  ngAfterViewInit(): void {
    this.scrollChat();
  }

  setTab(t: AiTab): void {
    this.tab.set(t);
    if (t === 'recommendations' || t === 'corrections') this.loadRecommendations();
    else if (t === 'jira') this.loadJira();
    else this.scrollChat();
  }

  // ------------------------------------------------------- corrections

  markApplied(r: AiRecommendation): void {
    const reference = (this.references[r.id] ?? '').trim();
    if (!reference) {
      this.toasts.error('Référence requise', 'Indiquez le commit, la merge request ou le ticket de la correction.');
      return;
    }
    this.api.markApplied(r.id, reference).subscribe({
      next: () => {
        delete this.references[r.id];
        this.toasts.success('Correction déclarée appliquée', 'Elle sera vérifiée sur les prochains logs, tests ou scans.');
        this.loadRecommendations(true);
      },
      error: (e) => this.toasts.error('Déclaration impossible', errorMessage(e)),
    });
  }

  verify(r: AiRecommendation): void {
    this.api.verifyCorrection(r.id).subscribe({
      next: (updated) => {
        this.toasts.show(updated.fixStatus === 'VERIFIED' ? 'success' : updated.fixStatus === 'STILL_PRESENT' ? 'warning' : 'info',
          'Vérification effectuée', updated.verificationDetails ?? undefined);
        this.loadRecommendations(true);
      },
      error: (e) => this.toasts.error('Vérification impossible', errorMessage(e)),
    });
  }

  // -------------------------------------------------------------- Jira

  loadJira(): void {
    this.api.jiraStatus().subscribe({ next: (s) => this.jiraStatus.set(s), error: () => this.jiraStatus.set(null) });
    this.api.jiraActions().subscribe({
      next: (a) => this.jiraActions.set(a),
      error: (e) => this.toasts.error('Actions Jira indisponibles', errorMessage(e)),
    });
  }

  approveJira(a: JiraAction): void {
    this.api.jiraApprove(a.id, this.jiraComments[a.id] || undefined).subscribe({
      next: (res) => {
        delete this.jiraComments[a.id];
        if (res.status === 'FAILED') this.toasts.error('Exécution Jira en échec', res.error ?? undefined);
        else this.toasts.success(res.status === 'SIMULATED' ? 'Action approuvée (simulation)' : 'Action exécutée dans Jira',
          res.resultKey ?? undefined);
        this.loadJira();
      },
      error: (e) => this.toasts.error('Approbation impossible', errorMessage(e)),
    });
  }

  rejectJira(a: JiraAction): void {
    const comment = (this.jiraComments[a.id] ?? '').trim();
    if (!comment) {
      this.toasts.error('Motif requis', 'Un rejet doit être motivé.');
      return;
    }
    this.api.jiraReject(a.id, comment).subscribe({
      next: () => {
        delete this.jiraComments[a.id];
        this.toasts.success('Action rejetée');
        this.loadJira();
      },
      error: (e) => this.toasts.error('Rejet impossible', errorMessage(e)),
    });
  }

  canApprove(a: JiraAction): boolean {
    return this.auth.canManageSecurity() && !(this.jiraStatus()?.fourEyes && a.proposedBy === this.auth.user()?.username);
  }

  loadRecommendations(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.recommendations().subscribe({
      next: (r) => {
        this.recommendations.set(r);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        if (!silent) this.toasts.error('Recommandations indisponibles', errorMessage(e));
      },
    });
  }

  review(r: AiRecommendation, status: 'APPROVED' | 'REJECTED'): void {
    this.api.reviewRecommendation(r.id, status, this.comments[r.id] || undefined).subscribe({
      next: () => {
        this.toasts.success(status === 'APPROVED' ? 'Recommandation validée' : 'Recommandation rejetée');
        delete this.comments[r.id];
        this.loadRecommendations(true);
      },
      error: (e) => this.toasts.error('Échec', errorMessage(e)),
    });
  }

  ask(text?: string): void {
    const q = text ?? this.question;
    if (!q.trim() || this.thinking()) return;
    this.store.ask(q);
    if (text === undefined) this.question = '';
  }

  resetChat(): void {
    if (confirmClear()) this.store.clear();
  }

  typeLabel(t: string): string {
    return ({ log_anomaly: 'Anomalie de log', test_failure: 'Échec de test', vulnerability: 'Vulnérabilité' } as Record<string, string>)[t] ?? 'Autre';
  }

  private scrollChat(): void {
    setTimeout(() => {
      const el = this.chatLog()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}

function confirmClear(): boolean {
  return typeof window === 'undefined' || window.confirm('Effacer toute la conversation avec le copilote ?');
}
