import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, debounceTime, filter, interval } from 'rxjs';
import { ApiService, errorMessage, saveBlob } from '../../core/api.service';
import { EventsService } from '../../core/events.service';
import { ApplicationView, PortfolioSummary, ValidationCampaign } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SparklineComponent } from '../../shared/charts';
import { SHARED_PIPES } from '../../shared/pipes';
import { ScoreRingComponent } from '../../shared/score-ring.component';

/**
 * Validation multi-applications : sélection de N applications, campagne asynchrone,
 * un rapport indépendant par application, tableau de bord récapitulatif du portefeuille.
 */
@Component({
  selector: 'app-validation',
  standalone: true,
  imports: [FormsModule, RouterLink, ScoreRingComponent, SparklineComponent, ...SHARED_PIPES],
  templateUrl: './validation.component.html',
})
export class ValidationComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly events = inject(EventsService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly portfolio = signal<PortfolioSummary | null>(null);
  readonly campaigns = signal<ValidationCampaign[]>([]);
  readonly selected = signal<Set<string>>(new Set());
  readonly running = signal<ValidationCampaign | null>(null);
  readonly expanded = signal<number | null>(null);
  readonly launching = signal(false);
  mode: 'LATEST_RESULTS' | 'RUN_TESTS' = 'LATEST_RESULTS';
  useLlm = true;
  label = '';
  private poll: Subscription | null = null;

  readonly apps = computed<ApplicationView[]>(() => (this.portfolio()?.entries ?? []).map((e) => e.application));
  readonly selectedCount = computed(() => this.selected().size);
  readonly allSelected = computed(() => this.apps().length > 0 && this.selected().size === this.apps().length);
  /** Applications les plus à risque d'abord : rejetées, non concluantes, avec réserves, jamais validées, validées. */
  readonly ranked = computed(() => {
    const order: Record<string, number> = { REJECTED: 0, INCONCLUSIVE: 1, VALIDATED_WITH_RESERVES: 2, VALIDATED: 4 };
    return [...(this.portfolio()?.entries ?? [])].sort((a, b) => {
      const ra = a.lastValidation?.finalVerdict ? order[a.lastValidation.finalVerdict] : 3;
      const rb = b.lastValidation?.finalVerdict ? order[b.lastValidation.finalVerdict] : 3;
      return ra - rb || (a.lastValidation?.score ?? 0) - (b.lastValidation?.score ?? 0);
    });
  });
  readonly progress = computed(() => {
    const c = this.running();
    if (!c) return 0;
    const done = c.results.filter((r) => r.state === 'DONE' || r.state === 'ERROR').length;
    return c.total === 0 ? 0 : Math.round((100 * done) / c.total);
  });

  ngOnInit(): void {
    const preselect = this.route.snapshot.queryParamMap.get('apps');
    if (preselect) this.selected.set(new Set(preselect.split(',').filter((s) => s.length > 0)));
    this.load();
    this.api.runningJob('validation').subscribe({
      next: (job) => {
        if (job) this.api.campaigns(5).subscribe((list) => {
          const current = list.find((c) => c.status === 'RUNNING');
          if (current) this.watch(current);
        });
      },
      error: () => undefined,
    });
    this.events.events$.pipe(
      filter((e) => e.type.startsWith('validation.')),
      debounceTime(800),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(() => this.load());
    this.destroyRef.onDestroy(() => this.poll?.unsubscribe());
  }

  load(): void {
    this.api.portfolio().subscribe({
      next: (p) => this.portfolio.set(p),
      error: (e) => this.toasts.error('Tableau de bord indisponible', errorMessage(e)),
    });
    this.api.campaigns(15).subscribe({ next: (c) => this.campaigns.set(c), error: () => undefined });
  }

  toggle(code: string): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
  }

  toggleAll(): void {
    this.selected.set(this.allSelected() ? new Set() : new Set(this.apps().map((a) => a.code)));
  }

  isSelected(code: string): boolean {
    return this.selected().has(code);
  }

  launch(): void {
    if (!this.selectedCount()) return;
    this.launching.set(true);
    this.api.startValidation([...this.selected()], this.mode, this.useLlm, this.label || undefined).subscribe({
      next: (c) => {
        this.launching.set(false);
        this.toasts.info('Campagne lancée', `${c.total} application(s) : un rapport sera produit pour chacune.`);
        this.watch(c);
      },
      error: (e) => {
        this.launching.set(false);
        this.toasts.error('Lancement impossible', errorMessage(e));
      },
    });
  }

  /** Suit l'avancement application par application jusqu'à la fin de la campagne. */
  private watch(c: ValidationCampaign): void {
    this.running.set(c);
    this.poll?.unsubscribe();
    this.poll = interval(2000).subscribe(() => {
      this.api.campaign(c.id).subscribe({
        next: (current) => {
          this.running.set(current);
          if (current.status !== 'RUNNING') {
            this.poll?.unsubscribe();
            this.poll = null;
            this.running.set(null);
            this.expanded.set(current.id);
            this.load();
            this.toasts.success('Validation terminée', `${current.validated} validée(s), ${current.reserves} avec réserves, `
              + `${current.rejected} rejetée(s), ${current.inconclusive} non concluante(s).`);
          }
        },
        error: () => undefined,
      });
    });
  }

  toggleCampaign(id: number): void {
    this.expanded.update((e) => (e === id ? null : id));
  }

  downloadZip(c: ValidationCampaign): void {
    this.api.downloadValidation(`campaigns/${c.id}/reports.zip`).subscribe({
      next: (blob) => saveBlob(blob, `rapports-campagne-${c.id}.zip`),
      error: (e) => this.toasts.error('Téléchargement impossible', errorMessage(e)),
    });
  }

  download(id: number, code: string, ext: 'md' | 'html'): void {
    this.api.downloadValidation(`${id}/report.${ext}`).subscribe({
      next: (blob) => saveBlob(blob, `rapport-validation-${code}.${ext}`),
      error: (e) => this.toasts.error('Téléchargement impossible', errorMessage(e)),
    });
  }

  verdictOf(app: string): string | null {
    return this.portfolio()?.entries.find((e) => e.application.code === app)?.lastValidation?.finalVerdict ?? null;
  }
}
