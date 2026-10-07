/** Contrats TypeScript alignés sur les DTO du backend Spring Boot. */

export type Role = 'DEVELOPER' | 'QUALITY_MANAGER' | 'ADMIN';
export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL' | 'UNKNOWN';
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';
export type VulnStatus = 'NEW' | 'IN_PROGRESS' | 'FIXED' | 'ACCEPTED_RISK' | 'FALSE_POSITIVE';
export type ValidationStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
export type IncidentType = 'log_anomaly' | 'test_failure' | 'vulnerability' | 'other';

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
}

export interface ExplainRequest {
  incidentType: IncidentType;
  source?: string | null;
  message: string;
  occurrences?: number | null;
  extraContext?: string | null;
  incidentRef?: string | null;
  force?: boolean;
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
