/** Contrats TypeScript alignés sur les DTO du backend Spring Boot. */

export type Role = 'DEVELOPER' | 'QUALITY_MANAGER' | 'ADMIN';
export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL' | 'UNKNOWN';
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';
export type VulnStatus = 'NEW' | 'IN_PROGRESS' | 'FIXED' | 'ACCEPTED_RISK' | 'FALSE_POSITIVE';
export type ValidationStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
export type IncidentType = 'log_anomaly' | 'test_failure' | 'vulnerability' | 'other';
export type LogType = 'APPLICATION' | 'SERVER' | 'CICD';
export type Criticality = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Verdict = 'VALIDATED' | 'VALIDATED_WITH_RESERVES' | 'REJECTED' | 'INCONCLUSIVE';
export type FixStatus = 'NONE' | 'APPLIED' | 'VERIFIED' | 'STILL_PRESENT' | 'NOT_ENOUGH_DATA';

export interface UserView {
  id: number;
  username: string;
  fullName: string | null;
  email: string | null;
  role: Role;
  enabled: boolean;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: UserView;
}

export interface UserUpsert {
  username?: string;
  fullName?: string;
  email?: string;
  role?: Role;
  password?: string;
  enabled?: boolean;
}

export interface LogEntry {
  id?: number;
  timestamp: string;
  level: LogLevel;
  source: string;
  message: string;
  stackTrace?: string | null;
  application?: string | null;
  logType?: LogType | null;
}

export interface LogPage {
  content: LogEntry[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface TimeBucket {
  start: string;
  total: number;
  errors: number;
  warnings: number;
}

export interface LogStats {
  total: number;
  byLevel: Record<string, number>;
  bySource: Record<string, number>;
  timeline: TimeBucket[];
  firstAt: string | null;
  lastAt: string | null;
  errorRatePercent: number;
}

export interface AnomalyGroup {
  key: string;
  normalizedMessage: string;
  level: LogLevel;
  occurrences: number;
  examples: LogEntry[];
  critical: boolean;
  affectedSources: string[];
  summary: string;
  category: string;
  firstSeen: string;
  lastSeen: string;
  logType?: LogType | null;
  layers?: LogType[] | null;
  errorCode?: string | null;
}

export interface FrequencySpike {
  source: string;
  bucketStart: string;
  count: number;
  baseline: number;
  score: number;
  method: string;
}

export interface SpikeReport {
  method: string;
  bucketMinutes: number;
  spikes: FrequencySpike[];
  timeline: TimeBucket[];
}

export interface IngestResult {
  ingested: number;
  skippedLines: number;
  totalInStore: number;
  criticalAnomalies: number;
}

export interface TestCaseResult {
  name: string;
  status: 'PASSED' | 'FAILED' | 'ERROR' | 'SKIPPED';
  failureMessage: string | null;
  timeSeconds: number;
  stackTrace?: string | null;
}

export interface SuiteResult {
  suiteName: string;
  tests: number;
  failures: number;
  errors: number;
  skipped: number;
  timeSeconds: number;
  testCases: TestCaseResult[];
}

export interface TestExecutionSummary {
  totalTests: number;
  totalFailures: number;
  totalErrors: number;
  totalSkipped: number;
  successRatePercent: number;
  suites: SuiteResult[];
  runId: number | null;
  executedAt: string | null;
  trigger: string | null;
  branch: string | null;
  commitSha: string | null;
  lineCoverage: number | null;
  branchCoverage: number | null;
  durationSeconds: number;
  application?: string | null;
}

export interface TestRunPoint {
  runId: number;
  executedAt: string;
  total: number;
  failed: number;
  successRate: number;
  lineCoverage: number | null;
  trigger: string;
  branch: string | null;
}

export interface FlakyTest {
  suiteName: string;
  testName: string;
  runsObserved: number;
  failures: number;
  statusChanges: number;
  flakinessPercent: number;
  lastStatus: string;
}

export interface CorrelatedFailure {
  suiteName: string;
  testCaseName: string;
  failureMessage: string;
  relatedLogs: LogEntry[];
  correlationNote: string;
}

export interface Vulnerability {
  recordId: number;
  id: string;
  source: string;
  affectedComponent: string;
  severity: Severity;
  cvssScore: number | null;
  description: string;
  status: VulnStatus;
  fixedVersion: string | null;
  priorityScore: number;
  firstDetectedAt: string | null;
  lastDetectedAt: string | null;
  resolvedAt: string | null;
  autoResolved: boolean;
  reopened: boolean;
  assignee: string | null;
  note: string | null;
  applicationCode?: string | null;
}

export interface VulnerabilityStats {
  open: number;
  fixed: number;
  total: number;
  openBySeverity: Record<string, number>;
  openBySource: Record<string, number>;
  byStatus: Record<string, number>;
  meanTimeToFixDays: number | null;
}

export interface ScanDiff {
  source: string;
  detected: number;
  newCount: number;
  updatedCount: number;
  resolvedCount: number;
  reopenedCount: number;
}

export interface Job {
  id: string;
  type: string;
  label: string;
  triggeredBy: string;
  startedAt: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  finishedAt: string | null;
  message: string | null;
  result: Record<string, unknown> | null;
}

/** État d'un prérequis côté serveur (Maven, Trivy, Docker, SonarQube, clés d'API). */
export interface ToolStatus {
  label: string;
  available: boolean;
  detail: string;
  hint: string | null;
}

export interface SystemTools {
  tools: Record<string, ToolStatus>;
  defaultImage: string;
}

export interface AiRecommendation {
  id: string;
  incidentType: IncidentType;
  incidentSummary: string;
  explanation: string;
  suggestedFix: string;
  severityAssessment: string;
  status: ValidationStatus;
  createdAt: string;
  incidentRef: string | null;
  source: string | null;
  provider: string | null;
  model: string | null;
  createdBy: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  applicationCode?: string | null;
  fixStatus?: FixStatus | null;
  appliedBy?: string | null;
  appliedAt?: string | null;
  appliedReference?: string | null;
  verifiedAt?: string | null;
  verificationDetails?: string | null;
  diagnostic?: string | null;
}

export interface ExplainRequest {
  incidentType: IncidentType;
  source?: string | null;
  message: string;
  occurrences?: number | null;
  extraContext?: string | null;
  incidentRef?: string | null;
  force?: boolean;
  application?: string | null;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  provider?: string;
}

export interface ChatResponse {
  answer: string;
  provider: string;
  model: string;
  answeredAt: string;
}

export interface GateCondition {
  name: string;
  actual: string;
  threshold: string;
  passed: boolean;
}

export interface QualityGateResult {
  status: 'PASSED' | 'FAILED';
  conditions: GateCondition[];
  evaluatedAt: string;
}

export interface ServiceStatus {
  status: string;
  provider?: string;
  model?: string;
  llmConfigured?: boolean;
  detail?: string;
}

export interface DashboardOverview {
  healthScore: number;
  healthLabel: string;
  logs: {
    total: number;
    errors: number;
    warnings: number;
    anomalyGroups: number;
    criticalAnomalies: number;
    spikes: number;
    lastIngestAt: string | null;
  };
  tests: {
    total: number;
    failed: number;
    successRate: number;
    lineCoverage: number | null;
    executedAt: string | null;
    trend: TestRunPoint[];
    flaky: number;
  };
  security: VulnerabilityStats;
  ai: { pending: number; approved: number; rejected: number };
  services: Record<string, ServiceStatus>;
  topAnomalies: AnomalyGroup[];
  topVulnerabilities: Vulnerability[];
  qualityGate: QualityGateResult;
  recentJobs: Job[];
}

export interface AuditEntry {
  id: number;
  at: string;
  username: string;
  action: string;
  target: string | null;
  details: string | null;
}

export interface ServerEvent {
  type: string;
  severity: 'info' | 'success' | 'warning' | 'critical';
  title: string;
  data: Record<string, unknown>;
  at: string;
}

// ------------------------------------------------------------- multi-applications
export interface ApplicationView {
  id: number;
  code: string;
  name: string;
  description: string | null;
  owner: string | null;
  criticality: Criticality;
  serverType: string | null;
  projectPath: string | null;
  reportsPath: string | null;
  coveragePath: string | null;
  logSources: string | null;
  jiraProjectKey: string | null;
  repositoryUrl: string | null;
  policyOverrides: string | null;
  active: boolean;
  defaultApplication: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export type ApplicationUpsert = Partial<Omit<ApplicationView, 'id' | 'defaultApplication' | 'createdAt' | 'updatedAt'>>;

// --------------------------------------------------------- diagnostic explicable
export interface ExplanationFactor {
  code: string;
  label: string;
  value: number;
  weight: number;
  contribution: number;
  detail: string;
}

export interface IncidentSignal {
  key: string;
  message: string;
  layer: LogType;
  source: string;
  level: string;
  errorCode: string | null;
  category: string;
  occurrences: number;
  firstSeen: string;
  lastSeen: string;
  example: string;
}

export interface RootCauseCandidate {
  signal: IncidentSignal;
  score: number;
  confidencePercent: number;
  factors: ExplanationFactor[];
  rule: string | null;
  rationale: string;
}

export interface CausalLink {
  fromKey: string;
  fromLabel: string;
  toKey: string;
  toLabel: string;
  rule: string;
  explanation: string;
}

export interface CorrelatedIncident {
  id: string;
  start: string;
  end: string;
  severity: string;
  layers: LogType[];
  sources: string[];
  eventCount: number;
  signals: IncidentSignal[];
  rootCause: RootCauseCandidate;
  alternatives: RootCauseCandidate[];
  chain: CausalLink[];
  impactedTests: string[];
  category: string;
  summary: string;
}

export interface LogBreakdown {
  byType: Record<string, number>;
  byApplication: Record<string, number>;
}

// ------------------------------------------------------------------- validation
export interface CriterionResult {
  code: string;
  label: string;
  category: string;
  status: 'PASS' | 'WARN' | 'FAIL' | 'NO_DATA';
  score: number;
  baseWeight: number;
  effectiveWeight: number;
  evidenceConfidence: number;
  blocking: boolean;
  blockingFailed: boolean;
  measured: string;
  threshold: string;
  justification: string;
  adaptation: string | null;
  pointsLost: number;
}

export interface DecisionStep {
  rule: string;
  outcome: string;
  detail: string;
}

export interface LlmAssessment {
  status: 'OK' | 'USED' | 'IGNORED' | 'UNAVAILABLE' | 'DISABLED';
  opinion: string | null;
  confidence: number;
  proposedAdjustment: number;
  appliedAdjustment: number;
  allowedAdjustment: number;
  justification: string | null;
  risks: string[];
  recommendations: string[];
  citedRefs: string[];
  ungroundedRefs: string[];
  provider: string | null;
  model: string | null;
  note: string | null;
}

export interface RiskItem {
  level: string;
  title: string;
  detail: string;
  source: string;
}

export interface RecommendationItem {
  priority: number;
  action: string;
  rationale: string;
  source: string;
  ref: string | null;
}

export interface TestFailureView {
  ref: string;
  suite: string;
  test: string;
  status: string;
  message: string | null;
  stackExcerpt: string | null;
  relatedLogs: string[];
  newFailure: boolean;
}

export interface AnomalyView {
  key: string;
  message: string;
  level: string;
  layer: LogType | null;
  category: string;
  errorCode: string | null;
  occurrences: number;
  critical: boolean;
  sources: string[];
  lastSeen: string | null;
  example: string | null;
}

export interface ValidationReport {
  applicationCode: string;
  applicationName: string;
  criticality: Criticality;
  owner: string | null;
  serverType: string | null;
  campaignId: number;
  validationId: number;
  generatedAt: string;
  generatedBy: string;
  mode: string;
  verdict: Verdict;
  score: number;
  baseScore: number;
  llmAdjustment: number;
  confidence: number;
  reviewRequired: boolean;
  reviewReasons: string[];
  blockingReasons: string[];
  criteria: CriterionResult[];
  trace: DecisionStep[];
  policy: Record<string, string>;
  tests: {
    available: boolean; runId: number | null; executedAt: string | null; trigger: string | null; total: number; failed: number;
    skipped: number; successRate: number; lineCoverage: number | null; branchCoverage: number | null; durationSeconds: number;
    ageHours: number; failures: TestFailureView[]; newFailures: string[]; fixedTests: string[]; flaky: FlakyTest[];
    trend: TestRunPoint[];
  };
  logs: {
    available: boolean; total: number; errors: number; warnings: number; errorRatePercent: number;
    byLayer: Record<string, number>; errorsByLayer: Record<string, number>; criticalAnomalies: number; spikes: number;
    anomalies: AnomalyView[]; excerpts: string[];
  };
  incidents: CorrelatedIncident[];
  security: { available: boolean; open: number; openBySeverity: Record<string, number>; top: Vulnerability[] };
  risks: RiskItem[];
  recommendations: RecommendationItem[];
  llm: LlmAssessment | null;
  previousScore: number | null;
  previousVerdict: string | null;
}

export interface ApplicationValidationSummary {
  id: number;
  campaignId: number;
  applicationCode: string;
  applicationName: string;
  criticality: Criticality;
  state: 'PENDING' | 'RUNNING' | 'DONE' | 'ERROR';
  verdict: Verdict | null;
  finalVerdict: Verdict | null;
  score: number | null;
  baseScore: number | null;
  llmAdjustment: number | null;
  confidence: number | null;
  reviewRequired: boolean;
  testsTotal: number | null;
  testsFailed: number | null;
  successRate: number | null;
  lineCoverage: number | null;
  openCritical: number | null;
  openHigh: number | null;
  criticalAnomalies: number | null;
  incidents: number | null;
  logErrors: number | null;
  llmStatus: string | null;
  llmOpinion: string | null;
  llmProvider: string | null;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  humanDecision: 'CONFIRMED' | 'OVERRIDDEN' | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
}

export interface ValidationCampaign {
  id: number;
  label: string;
  mode: 'LATEST_RESULTS' | 'RUN_TESTS';
  useLlm: boolean;
  status: 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED';
  applications: string[];
  triggeredBy: string;
  startedAt: string;
  finishedAt: string | null;
  total: number;
  validated: number;
  reserves: number;
  rejected: number;
  inconclusive: number;
  errors: number;
  averageScore: number | null;
  jobId: string | null;
  results: ApplicationValidationSummary[];
}

export interface ApplicationValidationDetail {
  summary: ApplicationValidationSummary;
  report: ValidationReport | null;
}

export interface PortfolioEntry {
  application: ApplicationView;
  lastValidation: ApplicationValidationSummary | null;
  scoreTrend: number[];
}

export interface PortfolioSummary {
  applications: number;
  validated: number;
  reserves: number;
  rejected: number;
  inconclusive: number;
  neverValidated: number;
  pendingReviews: number;
  averageScore: number | null;
  entries: PortfolioEntry[];
  generatedAt: string;
}

// ------------------------------------------------------------------------- Jira
export interface JiraIssue {
  key: string;
  summary: string;
  status: string;
  priority: string;
  assignee: string;
  updated: string;
  labels: string[];
  url: string | null;
}

export interface JiraMatch {
  issue: JiraIssue;
  score: number;
  reason: string;
}

export interface JiraIncidentQuery {
  application?: string | null;
  incidentType?: string | null;
  incidentRef?: string | null;
  text?: string | null;
  recommendationId?: string | null;
}

export interface JiraProposal {
  type: 'CREATE_ISSUE' | 'ADD_COMMENT';
  application: string | null;
  projectKey: string | null;
  incidentType: string | null;
  incidentRef: string | null;
  issueKey: string | null;
  summary: string | null;
  description: string | null;
  comment: string | null;
  recommendationId: string | null;
}

export interface JiraStatus {
  enabled: boolean;
  configured: boolean;
  mode: 'LIVE' | 'SIMULATION';
  deployment: string;
  baseUrl: string | null;
  defaultProject: string | null;
  issueType: string;
  fourEyes: boolean;
}

export interface JiraAction {
  id: number;
  type: 'CREATE_ISSUE' | 'ADD_COMMENT';
  status: 'PROPOSED' | 'EXECUTED' | 'SIMULATED' | 'REJECTED' | 'FAILED';
  applicationCode: string | null;
  projectKey: string | null;
  incidentType: string | null;
  incidentRef: string | null;
  fingerprint: string | null;
  issueKey: string | null;
  summary: string | null;
  description: string | null;
  comment: string | null;
  recommendationId: string | null;
  proposedBy: string;
  proposedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  executedAt: string | null;
  resultKey: string | null;
  resultUrl: string | null;
  error: string | null;
}
