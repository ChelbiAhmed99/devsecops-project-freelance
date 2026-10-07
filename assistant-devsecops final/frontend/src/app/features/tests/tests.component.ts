import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, errorMessage } from '../../core/api.service';
import { EventsService } from '../../core/events.service';
import { shortDateTime } from '../../core/format';
import {
  CorrelatedFailure, ExplainRequest, FlakyTest, GateCondition, Job, TestCaseResult, TestExecutionSummary,
  TestRunPoint, ToolStatus,
} from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { LineChartComponent, LineSeries } from '../../shared/charts';
import { ExplainDialogComponent } from '../../shared/explain-dialog.component';
import { SHARED_PIPES } from '../../shared/pipes';
import { ToolStatusComponent } from '../../shared/tool-status.component';

const TRIGGER_LABELS: Record<string, string> = {
  UI: 'lancée depuis l\'interface',
  IMPORT: 'rapports importés manuellement',
  CI: 'publiée par le pipeline CI/CD',
  DEMO: 'jeu de données de démonstration',
  DISK: 'rapports trouvés sur le serveur',
};

@Component({
  selector: 'app-tests',
  standalone: true,
  imports: [LineChartComponent, ExplainDialogComponent, ToolStatusComponent, ...SHARED_PIPES],
  templateUrl: './tests.component.html',
})
export class TestsComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly events = inject(EventsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly summary = signal<TestExecutionSummary | null>(null);
  readonly history = signal<TestRunPoint[]>([]);
  readonly flaky = signal<FlakyTest[]>([]);
  readonly correlated = signal<CorrelatedFailure[]>([]);
  readonly gate = signal<GateCondition[]>([]);
  readonly tools = signal<Record<string, ToolStatus> | null>(null);
  readonly job = signal<Job | null>(null);
  readonly elapsed = signal(0);
  readonly expandedSuite = signal<string | null>(null);
  readonly onlyFailures = signal(false);
  readonly explain = signal<ExplainRequest | null>(null);
  readonly importing = signal(false);
  readonly reportNames = signal<string[]>([]);
  readonly coverageName = signal<string | null>(null);
  private reportFiles: File[] = [];
  private coverageFile: File | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly running = computed(() => this.job()?.status === 'RUNNING');
  readonly failed = computed(() => {
    const s = this.summary();
    return s ? s.totalFailures + s.totalErrors : 0;
  });
  readonly hasResults = computed(() => (this.summary()?.totalTests ?? 0) > 0);
  readonly suites = computed(() => {
    const suites = this.summary()?.suites ?? [];
    return this.onlyFailures() ? suites.filter((s) => s.failures + s.errors > 0) : suites;
  });
  readonly latestRunId = computed(() => this.history().at(-1)?.runId ?? null);
  /** Vrai quand l'utilisateur consulte une ancienne exécution de l'historique. */
  readonly viewingOld = computed(() => {
    const current = this.summary()?.runId ?? null;
    const latest = this.latestRunId();
    return current !== null && latest !== null && current !== latest;
  });
  /** Le bouton « Lancer les tests » exige Maven et un projet (pom.xml) côté serveur. */
  readonly canRun = computed(() => {
    const t = this.tools();
    return !t || (t['maven']?.available !== false && t['project']?.available !== false);
  });
  readonly elapsedLabel = computed(() => {
    const s = this.elapsed();
    return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
  });
  readonly trendSeries = computed<LineSeries[]>(() => [
    { name: 'Réussite (%)', color: '#0e8a84', values: this.history().map((h) => h.successRate) },
    { name: 'Couverture lignes (%)', color: '#6b5bd6', values: this.history().map((h) => h.lineCoverage) },
  ]);
  readonly trendLabels = computed(() => this.history().map((h) => shortDateTime(h.executedAt)));

  ngOnInit(): void {
    this.load();
    this.api.systemTools().subscribe({ next: (t) => this.tools.set(t.tools), error: () => this.tools.set(null) });
    // Une exécution lancée avant un rechargement de page continue côté serveur : on reprend son suivi.
    this.api.runningJob('tests').subscribe({ next: (job) => { if (job) this.follow(job, false); }, error: () => undefined });
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  /** Recharge la dernière exécution et tout ce qui en dépend. */
  load(): void {
    this.api.testResults().subscribe({
      next: (s) => this.show(s),
      error: (e) => this.toasts.error('Résultats indisponibles', errorMessage(e)),
    });
    this.api.testHistory(20).subscribe({ next: (h) => this.history.set(h), error: () => this.history.set([]) });
    this.api.flakyTests().subscribe({ next: (f) => this.flaky.set(f), error: () => this.flaky.set([]) });
    this.api.correlatedFailures().subscribe({ next: (c) => this.correlated.set(c), error: () => this.correlated.set([]) });
    this.api.qualityGate().subscribe({
      next: (g) => this.gate.set(g.conditions.filter((c) => c.name.startsWith('Taux de réussite') || c.name.startsWith('Couverture'))),
      error: () => this.gate.set([]),
    });
  }

  /** Affiche une exécution de l'historique (résultats + corrélation aux logs de cette exécution). */
  loadRun(runId: number): void {
    this.api.testRun(runId).subscribe({
      next: (s) => this.show(s),
      error: (e) => this.toasts.error('Exécution introuvable', errorMessage(e)),
    });
    this.api.correlatedFailures(runId).subscribe({ next: (c) => this.correlated.set(c), error: () => this.correlated.set([]) });
  }

  run(): void {
    if (this.running()) return;
    this.api.runTests().subscribe({
      next: (job) => this.follow(job, true),
      error: (e) => this.toasts.error('Lancement impossible', errorMessage(e)),
    });
  }

  onReports(event: Event): void {
    this.reportFiles = Array.from((event.target as HTMLInputElement).files ?? []);
    this.reportNames.set(this.reportFiles.map((f) => f.name));
  }

  onCoverage(event: Event): void {
    this.coverageFile = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.coverageName.set(this.coverageFile?.name ?? null);
  }

  importReports(reportsInput: HTMLInputElement, coverageInput: HTMLInputElement): void {
    if (!this.reportFiles.length) {
      this.toasts.error('Aucun fichier sélectionné', 'Choisissez au moins un rapport JUnit XML (TEST-*.xml).');
      return;
    }
    this.importing.set(true);
    this.api.importTests(this.reportFiles, this.coverageFile).subscribe({
      next: (s) => {
        this.importing.set(false);
        this.toasts.success('Rapports importés', `${s.totalTests} tests, réussite ${s.successRatePercent} %`);
        this.reportFiles = [];
        this.coverageFile = null;
        this.reportNames.set([]);
        this.coverageName.set(null);
        reportsInput.value = '';
        coverageInput.value = '';
        this.load();
      },
      error: (e) => {
        this.importing.set(false);
        this.toasts.error('Import impossible', errorMessage(e));
      },
    });
  }

  toggleSuite(name: string): void {
    this.expandedSuite.update((s) => (s === name ? null : name));
  }

  explainFailure(f: CorrelatedFailure): void {
    const logs = f.relatedLogs.slice(0, 5).map((l) => `[${l.level}] ${l.source}: ${l.message}`).join('\n');
    this.explain.set({
      incidentType: 'test_failure',
      source: `${f.suiteName}#${f.testCaseName}`,
      message: f.failureMessage || 'Échec sans message',
      extraContext: logs ? `Logs corrélés pendant l'exécution :\n${logs}` : f.correlationNote,
      incidentRef: `test:${f.suiteName}#${f.testCaseName}`,
    });
  }

  /** Explication IA directement depuis un cas de test en échec dans la liste des suites. */
  explainTestCase(suiteName: string, tc: TestCaseResult): void {
    this.explain.set({
      incidentType: 'test_failure',
      source: `${suiteName}#${tc.name}`,
      message: tc.failureMessage || 'Échec sans message',
      extraContext: (tc.stackTrace ?? '').slice(0, 3000) || null,
      incidentRef: `test:${suiteName}#${tc.name}`,
    });
  }

  shortName(suite: string): string {
    return suite.split('.').pop() ?? suite;
  }

  triggerLabel(trigger: string | null): string {
    return trigger ? (TRIGGER_LABELS[trigger] ?? trigger) : 'origine inconnue';
  }

  private show(s: TestExecutionSummary): void {
    this.summary.set(s);
    // Ouvre d'emblée la première suite en échec : c'est ce que l'on vient chercher.
    const firstFailing = (s.suites ?? []).find((x) => x.failures + x.errors > 0);
    this.expandedSuite.set(firstFailing?.suiteName ?? null);
  }

  /** Suit un job jusqu'à sa fin : chronomètre, puis rechargement des résultats. */
  private follow(job: Job, announce: boolean): void {
    this.job.set(job);
    this.startTimer(job.startedAt);
    if (announce) {
      this.toasts.info('Tests lancés', 'Maven s\'exécute en arrière-plan : vous pouvez changer d\'écran.');
    }
    this.api.waitForJob(job.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (done) => {
        this.stopTimer();
        this.job.set(done);
        // La notification temps réel affiche déjà le résultat ; on ne double le message que si elle est coupée.
        if (!this.events.connected()) {
          if (done.status === 'SUCCEEDED') this.toasts.success('Exécution terminée', done.message ?? undefined);
          else this.toasts.error('Exécution en échec', 'Le détail est affiché en haut de l\'écran.');
        }
        this.load();
      },
      error: (e) => {
        this.stopTimer();
        this.job.set({ ...job, status: 'FAILED', message: `Suivi de l'exécution interrompu : ${errorMessage(e)}` });
      },
    });
  }

  private startTimer(startedAt: string): void {
    this.stopTimer();
    // Reprise après rechargement : on repart du temps déjà écoulé si la date du serveur est cohérente.
    const already = Math.round((Date.now() - new Date(startedAt).getTime()) / 1000);
    const offset = already > 0 && already < 7200 ? already : 0;
    const begin = Date.now();
    this.elapsed.set(offset);
    this.timer = setInterval(() => this.elapsed.set(offset + Math.round((Date.now() - begin) / 1000)), 1000);
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
