import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, errorMessage, saveBlob } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { shortDateTime } from '../../core/format';
import {
  AnomalyView, ApplicationValidationDetail, ApplicationValidationSummary, CorrelatedIncident, ExplainRequest,
  JiraIncidentQuery, TestFailureView, ValidationReport, Verdict, Vulnerability,
} from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { LineChartComponent, LineSeries } from '../../shared/charts';
import { ExplainDialogComponent } from '../../shared/explain-dialog.component';
import { IncidentCardComponent } from '../../shared/incident-card.component';
import { JiraDialogComponent } from '../../shared/jira-dialog.component';
import { SHARED_PIPES } from '../../shared/pipes';
import { ScoreRingComponent } from '../../shared/score-ring.component';

type Tab = 'synthese' | 'tests' | 'logs' | 'securite' | 'actions' | 'ia' | 'politique';

/** Rapport de validation d'UNE application : décision expliquée, preuves, risques, recommandations, revue humaine. */
@Component({
  selector: 'app-validation-report',
  standalone: true,
  imports: [FormsModule, RouterLink, ScoreRingComponent, LineChartComponent, IncidentCardComponent, ExplainDialogComponent,
    JiraDialogComponent, ...SHARED_PIPES],
  templateUrl: './validation-report.component.html',
})
export class ValidationReportComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  readonly auth = inject(AuthService);

  readonly detail = signal<ApplicationValidationDetail | null>(null);
  readonly history = signal<ApplicationValidationSummary[]>([]);
  readonly tab = signal<Tab>('synthese');
  readonly error = signal<string | null>(null);
  readonly explain = signal<ExplainRequest | null>(null);
  readonly jira = signal<JiraIncidentQuery | null>(null);
  readonly deciding = signal(false);
  overrideVerdict: Verdict = 'VALIDATED_WITH_RESERVES';
  decisionComment = '';

  readonly verdicts: Verdict[] = ['VALIDATED', 'VALIDATED_WITH_RESERVES', 'REJECTED', 'INCONCLUSIVE'];
  readonly report = computed<ValidationReport | null>(() => this.detail()?.report ?? null);
  readonly summary = computed(() => this.detail()?.summary ?? null);
  /** Explication « en cascade » : 100 points moins les points perdus par critère. */
  readonly waterfall = computed(() => (this.report()?.criteria ?? []).filter((c) => c.status !== 'NO_DATA' && c.pointsLost > 0));
  readonly layers = computed(() => Object.entries(this.report()?.logs.byLayer ?? {}).filter(([, n]) => n > 0));
  readonly errorsByLayer = computed(() => this.report()?.logs.errorsByLayer ?? {});
  readonly trendSeries = computed<LineSeries[]>(() => {
    const h = [...this.history()].reverse();
    return [{ name: 'Score de validation', color: '#f62e30', values: h.map((v) => v.score) }];
  });
  readonly trendLabels = computed(() => [...this.history()].reverse().map((v) => shortDateTime(v.finishedAt)));

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.load(id);
  }

  load(id: number): void {
    this.api.validation(id).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.error.set(null);
        this.api.validationHistory(d.summary.applicationCode, 12).subscribe({ next: (h) => this.history.set(h), error: () => undefined });
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  confirm(): void {
    this.decide('CONFIRM');
  }

  override(): void {
    if (this.decisionComment.trim().length < 10) {
      this.toasts.error('Justification requise', 'Expliquez la correction de la décision (10 caractères minimum).');
      return;
    }
    this.decide('OVERRIDE');
  }

  private decide(kind: 'CONFIRM' | 'OVERRIDE'): void {
    const s = this.summary();
    if (!s) return;
    this.deciding.set(true);
    this.api.decideValidation(s.id, kind, kind === 'OVERRIDE' ? this.overrideVerdict : undefined, this.decisionComment || undefined)
      .subscribe({
        next: () => {
          this.deciding.set(false);
          this.decisionComment = '';
          this.toasts.success(kind === 'CONFIRM' ? 'Décision confirmée' : 'Décision corrigée', 'Le rapport a été mis à jour et l\'action tracée.');
          this.load(s.id);
        },
        error: (e) => {
          this.deciding.set(false);
          this.toasts.error('Décision impossible', errorMessage(e));
        },
      });
  }

  download(ext: 'md' | 'html'): void {
    const s = this.summary();
    if (!s) return;
    this.api.downloadValidation(`${s.id}/report.${ext}`).subscribe({
      next: (blob) => saveBlob(blob, `rapport-validation-${s.applicationCode}.${ext}`),
      error: (e) => this.toasts.error('Téléchargement impossible', errorMessage(e)),
    });
  }

  /** Ouvre le rapport HTML dans un nouvel onglet (impression / « Enregistrer en PDF »). */
  print(): void {
    const s = this.summary();
    if (!s) return;
    this.api.downloadValidation(`${s.id}/report.html`).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(new Blob([blob], { type: 'text/html' }));
        window.open(url, '_blank', 'noopener');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (e) => this.toasts.error('Ouverture impossible', errorMessage(e)),
    });
  }

  // ------------------------------------------------- corrections et Jira

  explainFailure(f: TestFailureView): void {
    this.explain.set({
      incidentType: 'test_failure', source: f.suite, message: `${f.test} : ${f.message ?? 'échec'}`,
      extraContext: (f.stackExcerpt ?? '').slice(0, 3000), incidentRef: f.ref, application: this.summary()?.applicationCode,
    });
  }

  explainAnomaly(a: AnomalyView): void {
    this.explain.set({
      incidentType: 'log_anomaly', source: a.sources.join(', '), message: a.message, occurrences: a.occurrences,
      extraContext: `Couche : ${a.layer ?? 'APPLICATION'}. Catégorie : ${a.category}.${a.errorCode ? ' Code : ' + a.errorCode + '.' : ''}`,
      incidentRef: a.key, application: this.summary()?.applicationCode,
    });
  }

  explainIncident(i: CorrelatedIncident): void {
    const root = i.rootCause.signal;
    this.explain.set({
      incidentType: 'log_anomaly', source: root.source, message: root.message, occurrences: root.occurrences,
      extraContext: i.summary.slice(0, 2000), incidentRef: root.key, application: this.summary()?.applicationCode,
    });
  }

  explainVulnerability(v: Vulnerability): void {
    this.explain.set({
      incidentType: 'vulnerability', source: v.affectedComponent, message: `${v.id} (${v.severity}) : ${v.description ?? ''}`.slice(0, 1500),
      extraContext: v.fixedVersion ? `Version corrigée : ${v.fixedVersion}` : null,
      incidentRef: `vuln:${v.source}:${v.id}:${v.affectedComponent}`.slice(0, 250), application: this.summary()?.applicationCode,
    });
  }

  jiraFor(kind: 'test_failure' | 'log_anomaly' | 'vulnerability' | 'incident', ref: string, text: string): void {
    this.jira.set({ application: this.summary()?.applicationCode, incidentType: kind, incidentRef: ref, text });
  }

  jiraIncident(i: CorrelatedIncident): void {
    this.jiraFor('incident', i.rootCause.signal.key, i.rootCause.signal.message + '\n' + i.summary);
  }

  /** Paramètres de politique dans l'ordre fourni par le backend (le pipe keyvalue les trierait). */
  readonly policyEntries = computed(() => Object.entries(this.report()?.policy ?? {}));

  lines(list: string[]): string {
    return list.join('\n');
  }

  errorsFor(layer: string): number {
    return this.errorsByLayer()[layer] ?? 0;
  }

  pct(v: number | null | undefined): string {
    return v === null || v === undefined ? '—' : `${Math.round(v * 100)} %`;
  }
}
