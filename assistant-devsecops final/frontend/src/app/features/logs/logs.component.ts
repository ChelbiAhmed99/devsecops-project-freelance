import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, LogSearch, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { shortTime } from '../../core/format';
import { AnomalyGroup, ExplainRequest, LogPage, LogStats, SpikeReport } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { BarChartComponent, BarDatum } from '../../shared/charts';
import { ExplainDialogComponent } from '../../shared/explain-dialog.component';
import { SHARED_PIPES } from '../../shared/pipes';

type Tab = 'anomalies' | 'spikes' | 'explorer' | 'ingest';

@Component({
  selector: 'app-logs',
  standalone: true,
  imports: [FormsModule, BarChartComponent, ExplainDialogComponent, ...SHARED_PIPES],
  templateUrl: './logs.component.html',
})
export class LogsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  readonly auth = inject(AuthService);

  readonly tab = signal<Tab>('anomalies');
  readonly stats = signal<LogStats | null>(null);
  readonly anomalies = signal<AnomalyGroup[]>([]);
  readonly engine = signal<'local' | 'ml'>('local');
  readonly engineUsed = signal('jaccard');
  readonly loadingAnomalies = signal(false);
  readonly spikes = signal<SpikeReport | null>(null);
  readonly page = signal<LogPage | null>(null);
  readonly sources = signal<string[]>([]);
  readonly expanded = signal<string | null>(null);
  readonly explain = signal<ExplainRequest | null>(null);
  readonly categoryFilter = signal<string>('');
  readonly onlyCritical = signal(false);

  search: LogSearch = { level: '', source: '', q: '', page: 0, size: 50 };
  rawLogs = '';
  ingestSource = '';
  ingesting = signal(false);

  readonly categories = computed(() => [...new Set(this.anomalies().map((a) => a.category))].sort());
  readonly filteredAnomalies = computed(() => this.anomalies().filter((a) =>
    (!this.categoryFilter() || a.category === this.categoryFilter()) && (!this.onlyCritical() || a.critical)));

  readonly timelineBars = computed<BarDatum[]>(() => (this.stats()?.timeline ?? []).map((b) => ({
    label: shortTime(b.start), values: [b.total - b.errors, b.errors],
  })));

  readonly spikeBars = computed<BarDatum[]>(() => {
    const report = this.spikes();
    if (!report) return [];
    const spikeStarts = new Set(report.spikes.map((s) => s.bucketStart));
    return report.timeline.map((b) => ({
      label: shortTime(b.start), values: [b.warnings, b.errors], highlight: spikeStarts.has(b.start),
    }));
  });

  readonly levelEntries = computed(() => Object.entries(this.stats()?.byLevel ?? {}).filter(([, v]) => v > 0));
  readonly sourceEntries = computed(() =>
    Object.entries(this.stats()?.bySource ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 8));

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.api.logStats().subscribe({ next: (s) => this.stats.set(s), error: (e) => this.toasts.error('Statistiques indisponibles', errorMessage(e)) });
    this.api.logSources().subscribe((s) => this.sources.set(s));
    this.loadAnomalies();
    if (this.tab() === 'spikes') this.loadSpikes();
    if (this.tab() === 'explorer') this.runSearch();
  }

  selectTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'spikes' && !this.spikes()) this.loadSpikes();
    if (tab === 'explorer' && !this.page()) this.runSearch();
  }

  setEngine(engine: 'local' | 'ml'): void {
    this.engine.set(engine);
    this.loadAnomalies();
  }

  loadAnomalies(): void {
    this.loadingAnomalies.set(true);
    this.api.anomaliesWithEngine(this.engine()).subscribe({
      next: (res) => {
        this.anomalies.set(res.body ?? []);
        const used = res.headers.get('X-Clustering-Engine') ?? 'jaccard';
        this.engineUsed.set(used);
        if (this.engine() === 'ml' && used !== 'tfidf-dbscan') {
          this.toasts.info('Service IA indisponible', 'Regroupement local (Jaccard) utilisé à la place.');
        }
        this.loadingAnomalies.set(false);
      },
      error: (e) => {
        this.loadingAnomalies.set(false);
        this.toasts.error('Anomalies indisponibles', errorMessage(e));
      },
    });
  }

  loadSpikes(): void {
    this.api.spikes().subscribe({ next: (s) => this.spikes.set(s), error: (e) => this.toasts.error('Pics indisponibles', errorMessage(e)) });
  }

  runSearch(page = 0): void {
    this.search.page = page;
    this.api.searchLogs(this.search).subscribe({
      next: (p) => this.page.set(p),
      error: (e) => this.toasts.error('Recherche impossible', errorMessage(e)),
    });
  }

  filterBySource(source: string): void {
    this.search = { ...this.search, source, level: 'ERROR,FATAL', q: '' };
    this.selectTab('explorer');
    this.runSearch();
  }

  toggle(key: string): void {
    this.expanded.update((k) => (k === key ? null : key));
  }

  explainAnomaly(a: AnomalyGroup): void {
    const stack = a.examples.find((e) => e.stackTrace)?.stackTrace ?? '';
    this.explain.set({
      incidentType: 'log_anomaly',
      source: a.affectedSources.join(', '),
      message: a.normalizedMessage,
      occurrences: a.occurrences,
      extraContext: `Catégorie : ${a.category}. Première occurrence : ${a.firstSeen}, dernière : ${a.lastSeen}. ${stack}`.slice(0, 3500),
      incidentRef: a.key,
    });
  }

  ingest(): void {
    if (!this.rawLogs.trim()) return;
    this.ingesting.set(true);
    this.api.ingestLogs(this.rawLogs, this.ingestSource || undefined).subscribe({
      next: (r) => this.afterIngest(r.ingested, r.skippedLines, r.criticalAnomalies),
      error: (e) => {
        this.ingesting.set(false);
        this.toasts.error('Ingestion impossible', errorMessage(e));
      },
    });
  }

  upload(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.ingesting.set(true);
    this.api.uploadLogs(file, this.ingestSource || undefined).subscribe({
      next: (r) => this.afterIngest(r.ingested, r.skippedLines, r.criticalAnomalies),
      error: (e) => {
        this.ingesting.set(false);
        this.toasts.error('Import impossible', errorMessage(e));
      },
    });
    (event.target as HTMLInputElement).value = '';
  }

  clearAll(): void {
    if (!confirm('Supprimer tous les logs stockés ? Cette action est irréversible.')) return;
    this.api.clearLogs().subscribe({
      next: () => {
        this.toasts.success('Logs supprimés');
        this.refresh();
      },
      error: (e) => this.toasts.error('Suppression impossible', errorMessage(e)),
    });
  }

  private afterIngest(ingested: number, skipped: number, critical: number): void {
    this.ingesting.set(false);
    this.rawLogs = '';
    this.toasts.success(`${ingested} log(s) ingéré(s)`,
      `${skipped} ligne(s) non reconnue(s) · ${critical} anomalie(s) critique(s) au total`);
    this.spikes.set(null);
    this.refresh();
    this.tab.set('anomalies');
  }
}
