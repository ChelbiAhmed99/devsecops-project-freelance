import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, retry, switchMap, take, takeWhile, timer } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AiRecommendation, AnomalyGroup, ApplicationUpsert, ApplicationValidationDetail, ApplicationValidationSummary,
  ApplicationView, AuditEntry, ChatMessage, ChatResponse, CorrelatedFailure, CorrelatedIncident, DashboardOverview,
  ExplainRequest, FlakyTest, IngestResult, JiraAction, JiraIncidentQuery, JiraMatch, JiraProposal, JiraStatus, Job,
  LogBreakdown, LogPage, LogStats, LogType, PortfolioSummary, QualityGateResult, ScanDiff, SpikeReport, SystemTools,
  TestExecutionSummary, TestRunPoint, UserUpsert, UserView, ValidationCampaign, Verdict, Vulnerability,
  VulnerabilityStats,
} from './models';

export interface LogSearch {
  level?: string;
  source?: string;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  logType?: string;
  app?: string;
}

export interface VulnFilter {
  status?: string;
  severity?: string;
  source?: string;
  q?: string;
  app?: string;
}

/** Point d'accès unique à l'API REST du backend. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  // ---------------------------------------------------------------- dashboard
  overview(): Observable<DashboardOverview> {
    return this.http.get<DashboardOverview>(`${this.base}/dashboard/overview`);
  }

  qualityGate(): Observable<QualityGateResult> {
    return this.http.get<QualityGateResult>(`${this.base}/dashboard/quality-gate`);
  }

  // --------------------------------------------------------------------- logs
  searchLogs(criteria: LogSearch): Observable<LogPage> {
    return this.http.get<LogPage>(`${this.base}/logs/search`, { params: toParams(criteria) });
  }

  logStats(): Observable<LogStats> {
    return this.http.get<LogStats>(`${this.base}/logs/stats`);
  }

  logSources(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/logs/sources`);
  }

  anomalies(engine: 'local' | 'ml'): Observable<AnomalyGroup[]> {
    return this.http.get<AnomalyGroup[]>(`${this.base}/logs/anomalies`, { params: { engine } });
  }

  anomaliesWithEngine(engine: 'local' | 'ml') {
    return this.http.get<AnomalyGroup[]>(`${this.base}/logs/anomalies`, { params: { engine }, observe: 'response' });
  }

  spikes(): Observable<SpikeReport> {
    return this.http.get<SpikeReport>(`${this.base}/logs/spikes`);
  }

  ingestLogs(raw: string, source?: string, app?: string, logType?: LogType | ''): Observable<IngestResult> {
    return this.http.post<IngestResult>(`${this.base}/logs/ingest`, raw, {
      headers: { 'Content-Type': 'text/plain' },
      params: toParams({ source, app, logType }),
    });
  }

  uploadLogs(file: File, source?: string, app?: string, logType?: LogType | ''): Observable<IngestResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<IngestResult>(`${this.base}/logs/upload`, form, { params: toParams({ source, app, logType }) });
  }

  /** Incidents corrélés multi-couches avec cause racine explicable (toutes applications si app est vide). */
  incidents(app?: string | null): Observable<CorrelatedIncident[]> {
    return this.http.get<CorrelatedIncident[]>(`${this.base}/logs/incidents`, { params: toParams({ app }) });
  }

  logBreakdown(): Observable<LogBreakdown> {
    return this.http.get<LogBreakdown>(`${this.base}/logs/breakdown`);
  }

  clearLogs(): Observable<unknown> {
    return this.http.delete(`${this.base}/logs`);
  }

  // -------------------------------------------------------------------- tests
  runTests(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/tests/run`, {});
  }

  importTests(reports: File[], coverage?: File | null): Observable<TestExecutionSummary> {
    const form = new FormData();
    reports.forEach((f) => form.append('reports', f));
    if (coverage) form.append('coverage', coverage);
    return this.http.post<TestExecutionSummary>(`${this.base}/tests/import`, form);
  }

  testResults(): Observable<TestExecutionSummary> {
    return this.http.get<TestExecutionSummary>(`${this.base}/tests/results`);
  }

  testRun(id: number): Observable<TestExecutionSummary> {
    return this.http.get<TestExecutionSummary>(`${this.base}/tests/runs/${id}`);
  }

  testHistory(limit = 20): Observable<TestRunPoint[]> {
    return this.http.get<TestRunPoint[]>(`${this.base}/tests/history`, { params: { limit } });
  }

  flakyTests(): Observable<FlakyTest[]> {
    return this.http.get<FlakyTest[]>(`${this.base}/tests/flaky`);
  }

  /** Échecs corrélés aux logs pour une exécution donnée (la dernière si runId est absent). */
  correlatedFailures(runId?: number | null): Observable<CorrelatedFailure[]> {
    return this.http.get<CorrelatedFailure[]>(`${this.base}/tests/failures/correlated`, { params: toParams({ runId }) });
  }

  // ----------------------------------------------------------------- security
  vulnerabilities(filter: VulnFilter): Observable<Vulnerability[]> {
    return this.http.get<Vulnerability[]>(`${this.base}/security/vulnerabilities`, { params: toParams(filter) });
  }

  vulnerabilityStats(): Observable<VulnerabilityStats> {
    return this.http.get<VulnerabilityStats>(`${this.base}/security/stats`);
  }

  updateVulnerability(recordId: number, update: { status?: string; assignee?: string; note?: string }) {
    return this.http.patch<Vulnerability>(`${this.base}/security/vulnerabilities/${recordId}`, update);
  }

  scanDependencies(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/security/scan/dependencies`, {});
  }

  scanImage(image?: string): Observable<Job> {
    return this.http.post<Job>(`${this.base}/security/scan/images`, {}, { params: toParams({ image }) });
  }

  syncSonar(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/security/scan/sonar`, {});
  }

  importSecurityReport(type: 'dependency-check' | 'trivy', file: File): Observable<ScanDiff> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ScanDiff>(`${this.base}/security/import/${type}`, form);
  }

  // ----------------------------------------------------------------------- IA
  explain(request: ExplainRequest): Observable<AiRecommendation> {
    return this.http.post<AiRecommendation>(`${this.base}/ai/explain`, request);
  }

  recommendations(status?: string): Observable<AiRecommendation[]> {
    return this.http.get<AiRecommendation[]>(`${this.base}/ai/recommendations`, { params: toParams({ status }) });
  }

  reviewRecommendation(id: string, status: 'APPROVED' | 'REJECTED', comment?: string) {
    return this.http.patch<AiRecommendation>(`${this.base}/ai/recommendations/${id}/status`, { status, comment });
  }

  aiHealth(): Observable<{ status: string; provider?: string; model?: string }> {
    return this.http.get<{ status: string; provider?: string; model?: string }>(`${this.base}/ai/health`);
  }

  chat(question: string, history: ChatMessage[]): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.base}/ai/chat`, {
      question,
      history: history.map((m) => ({ role: m.role, content: m.content })),
    });
  }

  /** Une correction validée a été appliquée par un humain (référence obligatoire : commit, MR, ticket). */
  markApplied(id: string, reference: string, comment?: string) {
    return this.http.post<AiRecommendation>(`${this.base}/ai/recommendations/${id}/applied`, { reference, comment });
  }

  verifyCorrection(id: string) {
    return this.http.post<AiRecommendation>(`${this.base}/ai/recommendations/${id}/verify`, {});
  }

  // ------------------------------------------------------------- applications
  applications(includeInactive = false): Observable<ApplicationView[]> {
    return this.http.get<ApplicationView[]>(`${this.base}/applications`, { params: { inactive: includeInactive } });
  }

  createApplication(app: ApplicationUpsert): Observable<ApplicationView> {
    return this.http.post<ApplicationView>(`${this.base}/applications`, app);
  }

  updateApplication(code: string, app: ApplicationUpsert): Observable<ApplicationView> {
    return this.http.put<ApplicationView>(`${this.base}/applications/${encodeURIComponent(code)}`, app);
  }

  applicationQualityGate(code: string): Observable<QualityGateResult> {
    return this.http.get<QualityGateResult>(`${this.base}/applications/${encodeURIComponent(code)}/quality-gate`);
  }

  // --------------------------------------------------------------- validation
  startValidation(applications: string[], mode: 'LATEST_RESULTS' | 'RUN_TESTS', useLlm: boolean, label?: string) {
    return this.http.post<ValidationCampaign>(`${this.base}/validations/campaigns`, { applications, mode, useLlm, label });
  }

  campaigns(limit = 20): Observable<ValidationCampaign[]> {
    return this.http.get<ValidationCampaign[]>(`${this.base}/validations/campaigns`, { params: { limit } });
  }

  campaign(id: number): Observable<ValidationCampaign> {
    return this.http.get<ValidationCampaign>(`${this.base}/validations/campaigns/${id}`);
  }

  portfolio(): Observable<PortfolioSummary> {
    return this.http.get<PortfolioSummary>(`${this.base}/validations/portfolio`);
  }

  validation(id: number): Observable<ApplicationValidationDetail> {
    return this.http.get<ApplicationValidationDetail>(`${this.base}/validations/${id}`);
  }

  validationHistory(app: string, limit = 20): Observable<ApplicationValidationSummary[]> {
    return this.http.get<ApplicationValidationSummary[]>(`${this.base}/validations/history`, { params: { app, limit } });
  }

  decideValidation(id: number, decision: 'CONFIRM' | 'OVERRIDE', verdict?: Verdict, comment?: string) {
    return this.http.post<ApplicationValidationSummary>(`${this.base}/validations/${id}/decision`, { decision, verdict, comment });
  }

  /** Rapport d'une application (.md ou .html) ou archive ZIP des rapports d'une campagne. */
  downloadValidation(path: string): Observable<Blob> {
    return this.http.get(`${this.base}/validations/${path}`, { responseType: 'blob' });
  }

  // --------------------------------------------------------------------- Jira
  jiraStatus(): Observable<JiraStatus> {
    return this.http.get<JiraStatus>(`${this.base}/jira/status`);
  }

  jiraMatches(query: JiraIncidentQuery): Observable<JiraMatch[]> {
    return this.http.post<JiraMatch[]>(`${this.base}/jira/matches`, query);
  }

  jiraDraft(query: JiraIncidentQuery, type: 'CREATE_ISSUE' | 'ADD_COMMENT', issueKey?: string): Observable<JiraProposal> {
    return this.http.post<JiraProposal>(`${this.base}/jira/draft`, query, { params: toParams({ type, issueKey }) });
  }

  jiraPropose(proposal: JiraProposal): Observable<JiraAction> {
    return this.http.post<JiraAction>(`${this.base}/jira/actions`, proposal);
  }

  jiraActions(status?: string, app?: string): Observable<JiraAction[]> {
    return this.http.get<JiraAction[]>(`${this.base}/jira/actions`, { params: toParams({ status, app }) });
  }

  jiraApprove(id: number, comment?: string): Observable<JiraAction> {
    return this.http.post<JiraAction>(`${this.base}/jira/actions/${id}/approve`, { comment });
  }

  jiraReject(id: number, comment: string): Observable<JiraAction> {
    return this.http.post<JiraAction>(`${this.base}/jira/actions/${id}/reject`, { comment });
  }

  // --------------------------------------------------------------------- jobs
  job(id: string): Observable<Job> {
    return this.http.get<Job>(`${this.base}/jobs/${id}`);
  }

  jobs(): Observable<Job[]> {
    return this.http.get<Job[]>(`${this.base}/jobs`);
  }

  /** Job en cours d'un type donné, ou null : sert à reprendre le suivi après un rechargement de page. */
  runningJob(type: 'tests' | 'scan-dependencies' | 'scan-image' | 'sync-sonar' | 'validation'): Observable<Job | null> {
    return this.http.get<Job | null>(`${this.base}/jobs/running`, { params: { type } });
  }

  /**
   * Interroge un job toutes les 2 s jusqu'à sa fin ; émet l'état final.
   * Une coupure réseau passagère est tolérée (3 nouvelles tentatives) ; au-delà,
   * l'erreur est remontée pour que l'écran sorte de l'état « en cours ».
   */
  waitForJob(id: string): Observable<Job> {
    return timer(0, 2000).pipe(
      switchMap(() => this.job(id).pipe(retry({ count: 3, delay: 2000 }))),
      takeWhile((j) => j.status === 'RUNNING', true),
      filter((j) => j.status !== 'RUNNING'),
      take(1),
    );
  }

  // ------------------------------------------------------------------ système
  /** Prérequis disponibles côté serveur (Maven, Trivy, Docker, SonarQube, clés d'API). */
  systemTools(refresh = false): Observable<SystemTools> {
    return this.http.get<SystemTools>(`${this.base}/system/tools`, { params: { refresh } });
  }

  // ------------------------------------------------------------ admin / audit
  users(): Observable<UserView[]> {
    return this.http.get<UserView[]>(`${this.base}/users`);
  }

  createUser(user: UserUpsert): Observable<UserView> {
    return this.http.post<UserView>(`${this.base}/users`, user);
  }

  updateUser(id: number, user: UserUpsert): Observable<UserView> {
    return this.http.put<UserView>(`${this.base}/users/${id}`, user);
  }

  deleteUser(id: number): Observable<unknown> {
    return this.http.delete(`${this.base}/users/${id}`);
  }

  audit(limit = 200): Observable<AuditEntry[]> {
    return this.http.get<AuditEntry[]>(`${this.base}/audit`, { params: { limit } });
  }

  changePassword(currentPassword: string, newPassword: string) {
    return this.http.post<{ message: string }>(`${this.base}/auth/change-password`, { currentPassword, newPassword });
  }

  // ------------------------------------------------------------------ exports
  download(path: 'reports/vulnerabilities.csv' | 'reports/summary.md'): Observable<Blob> {
    return this.http.get(`${this.base}/${path}`, { responseType: 'blob' });
  }
}

function toParams(values: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/** Déclenche le téléchargement d'un Blob dans le navigateur. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Message d'erreur lisible à partir d'une réponse d'erreur HTTP du backend. */
export function errorMessage(err: unknown): string {
  const e = err as { status?: number; error?: { error?: string } | string; message?: string };
  if (e?.status === 0) return 'Backend injoignable : vérifiez qu\'il est démarré.';
  if (e?.error && typeof e.error === 'object' && e.error.error) return e.error.error;
  if (typeof e?.error === 'string' && e.error.length < 300) return e.error;
  if (e?.status === 403) return 'Action non autorisée pour votre rôle.';
  return e?.message ?? 'Erreur inattendue';
}
