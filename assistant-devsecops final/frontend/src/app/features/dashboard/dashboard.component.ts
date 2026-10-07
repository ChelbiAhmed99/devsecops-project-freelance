import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime, filter } from 'rxjs';
import { ApiService, errorMessage, saveBlob } from '../../core/api.service';
import { EventsService } from '../../core/events.service';
import { shortDateTime } from '../../core/format';
import { DashboardOverview, ExplainRequest } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { DonutChartComponent, DonutSlice, LineChartComponent, LineSeries, SEVERITY_COLORS } from '../../shared/charts';
import { ExplainDialogComponent } from '../../shared/explain-dialog.component';
import { SHARED_PIPES } from '../../shared/pipes';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, DonutChartComponent, LineChartComponent, ExplainDialogComponent, ...SHARED_PIPES],
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly events = inject(EventsService);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = signal<DashboardOverview | null>(null);
  readonly error = signal<string | null>(null);
  readonly explain = signal<ExplainRequest | null>(null);

  readonly healthClass = computed(() => {
    const s = this.data()?.healthScore ?? 0;
    return s >= 80 ? 'ok' : s >= 60 ? 'medium' : s >= 40 ? 'high' : 'critical';
  });

  readonly severitySlices = computed<DonutSlice[]>(() => {
    const bySev = this.data()?.security.openBySeverity ?? {};
    return ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].map((s) => ({
      label: s, value: bySev[s] ?? 0, color: SEVERITY_COLORS[s] ?? '#ccc',
    })).filter((s) => s.value > 0);
  });

  readonly testTrend = computed<LineSeries[]>(() => {
    const trend = this.data()?.tests.trend ?? [];
    return [
      { name: 'Taux de réussite (%)', color: '#0e8a84', values: trend.map((t) => t.successRate) },
      { name: 'Couverture (%)', color: '#6b5bd6', values: trend.map((t) => t.lineCoverage) },
    ];
  });

  readonly trendLabels = computed(() => (this.data()?.tests.trend ?? []).map((t) => shortDateTime(t.executedAt)));

  readonly serviceList = computed(() => {
    const labels: Record<string, string> = { backend: 'Backend', database: 'Base de données', iaService: 'Service IA', sonarqube: 'SonarQube' };
    return Object.entries(this.data()?.services ?? {}).map(([k, v]) => ({ name: labels[k] ?? k, ...v }));
  });

  ngOnInit(): void {
    this.load();
    // Rafraîchit automatiquement le tableau de bord à chaque événement temps réel.
    this.events.events$.pipe(
      filter((e) => e.type !== 'connected'),
      debounceTime(1500),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(() => this.load());
  }

  load(): void {
    this.api.overview().subscribe({
      next: (d) => {
        this.data.set(d);
        this.error.set(null);
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  downloadReport(): void {
    this.api.download('reports/summary.md').subscribe({
      next: (blob) => saveBlob(blob, `rapport-devsecops-${new Date().toISOString().slice(0, 10)}.md`),
      error: (e) => this.toasts.error('Export impossible', errorMessage(e)),
    });
  }

  explainAnomaly(a: DashboardOverview['topAnomalies'][number]): void {
    this.explain.set({
      incidentType: 'log_anomaly',
      source: a.affectedSources.join(', '),
      message: a.normalizedMessage,
      occurrences: a.occurrences,
      extraContext: `Catégorie : ${a.category}. ${a.examples?.[0]?.stackTrace ?? ''}`.slice(0, 3000),
      incidentRef: a.key,
    });
  }
}
