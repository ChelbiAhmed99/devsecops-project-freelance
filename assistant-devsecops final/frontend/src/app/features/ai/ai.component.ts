import { AfterViewInit, Component, DestroyRef, ElementRef, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AiChatStore } from '../../core/ai-chat.store';
import { ApiService, errorMessage } from '../../core/api.service';
import { EventsService } from '../../core/events.service';
import { AiRecommendation, ValidationStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SHARED_PIPES } from '../../shared/pipes';

/** Ordre d'affichage dans la vue « Toutes » : ce qui attend une action d'abord. */
const STATUS_ORDER: Record<ValidationStatus, number> = { PENDING_REVIEW: 0, APPROVED: 1, REJECTED: 2 };

@Component({
  selector: 'app-ai',
  standalone: true,
  imports: [FormsModule, ...SHARED_PIPES],
  templateUrl: './ai.component.html',
})
export class AiComponent implements OnInit, AfterViewInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly events = inject(EventsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly chatLog = viewChild<ElementRef<HTMLDivElement>>('chatLog');

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
    // Rafraîchit la liste dès qu'un traitement se termine côté serveur (nouvelles fiches possibles).
    this.events.events$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((e) => {
      if (e.type !== 'logs.ingested') this.loadRecommendations(true);
    });
  }

  ngAfterViewInit(): void {
    this.scrollChat();
  }

  setTab(t: 'copilot' | 'recommendations'): void {
    this.tab.set(t);
    if (t === 'recommendations') this.loadRecommendations();
    else this.scrollChat();
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
