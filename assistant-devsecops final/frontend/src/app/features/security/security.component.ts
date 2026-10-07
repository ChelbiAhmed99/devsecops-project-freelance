import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiService, VulnFilter, errorMessage, saveBlob } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { EventsService } from '../../core/events.service';
import { ExplainRequest, Job, ToolStatus, Vulnerability, VulnerabilityStats, VulnStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { DonutChartComponent, DonutSlice, SEVERITY_COLORS } from '../../shared/charts';
import { ExplainDialogComponent } from '../../shared/explain-dialog.component';
import { SHARED_PIPES } from '../../shared/pipes';
import { ToolStatusComponent } from '../../shared/tool-status.component';

type ScanKind = 'deps' | 'image' | 'sonar';

const JOB_TYPES: Record<ScanKind, 'scan-dependencies' | 'scan-image' | 'sync-sonar'> = {
  deps: 'scan-dependencies',
  image: 'scan-image',
  sonar: 'sync-sonar',
};

const SOURCE_LABELS: Record<string, string> = {
  'dependency-check': 'OWASP Dependency-Check',
  trivy: 'Trivy',
  sonarqube: 'SonarQube',
};

const SEVERITY_LABELS: Record<string, string> = {
  CRITICAL: 'Critique', HIGH: 'Haute', MEDIUM: 'Moyenne', LOW: 'Faible', UNKNOWN: 'Inconnue',
};

@Component({
  selector: 'app-security',
  standalone: true,
  imports: [FormsModule, DonutChartComponent, ExplainDialogComponent, ToolStatusComponent, ...SHARED_PIPES],
  templateUrl: './security.component.html',
})
export class SecurityComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly events = inject(EventsService);
  private readonly destroyRef = inject(DestroyRef);
  readonly auth = inject(AuthService);

  readonly vulns = signal<Vulnerability[]>([]);
  readonly stats = signal<VulnerabilityStats | null>(null);
  readonly selected = signal<Vulnerability | null>(null);
  readonly explain = signal<ExplainRequest | null>(null);
  readonly runningJobs = signal<Record<string, boolean>>({});
  /** Dernier résultat de chaque scan lancé pendant cette session (message de synthèse ou d'erreur). */
  readonly lastResults = signal<Record<string, { ok: boolean; message: string }>>({});
  readonly tools = signal<Record<string, ToolStatus> | null>(null);
  readonly defaultImage = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);

  filter: VulnFilter = { status: 'OPEN', severity: '', source: '', q: '' };
  image = '';
  importType: 'dependency-check' | 'trivy' = 'dependency-check';
  edit = { status: '' as VulnStatus | '', assignee: '', note: '' };

  readonly statuses: VulnStatus[] = ['NEW', 'IN_PROGRESS', 'FIXED', 'ACCEPTED_RISK', 'FALSE_POSITIVE'];

  readonly severitySlices = computed<DonutSlice[]>(() => {
    const s = this.stats()?.openBySeverity ?? {};
    return Object.keys(SEVERITY_COLORS).map((k) => ({ label: SEVERITY_LABELS[k] ?? k, value: s[k] ?? 0, color: SEVERITY_COLORS[k] ?? '#ccc' }))
      .filter((x) => x.value > 0);
  });

  readonly sourceSlices = computed<DonutSlice[]>(() => {
    const colors: Record<string, string> = { 'dependency-check': '#6b5bd6', trivy: '#0e8a84', sonarqube: '#e07b00' };
    return Object.entries(this.stats()?.openBySource ?? {}).map(([k, v]) => ({ label: SOURCE_LABELS[k] ?? k, value: v, color: colors[k] ?? '#999' }));
  });

  ngOnInit(): void {
    this.load();
    this.api.systemTools().subscribe({
      next: (t) => {
        this.tools.set(t.tools);
        this.defaultImage.set(t.defaultImage);
      },
      error: () => this.tools.set(null),
    });
    // Un scan lancé avant un rechargement de page continue côté serveur : on reprend son suivi.
    if (this.auth.canManageSecurity()) {
      (Object.keys(JOB_TYPES) as ScanKind[]).forEach((kind) => {
        this.api.runningJob(JOB_TYPES[kind]).subscribe({ next: (job) => { if (job) this.follow(kind, job); }, error: () => undefined });
      });
    }
  }

  load(): void {
    this.loading.set(true);
    this.api.vulnerabilities(this.filter).subscribe({
      next: (v) => {
        this.vulns.set(v);
        this.loading.set(false);
        const sel = this.selected();
        if (sel) this.selected.set(v.find((x) => x.recordId === sel.recordId) ?? sel);
      },
      error: (e) => {
        this.loading.set(false);
        this.toasts.error('Vulnérabilités indisponibles', errorMessage(e));
      },
    });
    this.api.vulnerabilityStats().subscribe({ next: (s) => this.stats.set(s), error: () => undefined });
  }

  /** Raccourci depuis les compteurs : filtre la liste sur un statut ou une sévérité. */
  quickFilter(status: string, severity: string): void {
    this.filter = { status, severity, source: '', q: '' };
    this.load();
  }

  resetFilters(): void {
    this.quickFilter('OPEN', '');
  }

  hasCustomFilters(): boolean {
    return this.filter.status !== 'OPEN' || !!this.filter.severity || !!this.filter.source || !!this.filter.q;
  }

  select(v: Vulnerability): void {
    this.selected.set(v);
    this.edit = { status: v.status, assignee: v.assignee ?? '', note: v.note ?? '' };
  }

  save(): void {
    const v = this.selected();
    if (!v) return;
    this.saving.set(true);
    this.api.updateVulnerability(v.recordId, {
      status: this.edit.status || undefined, assignee: this.edit.assignee, note: this.edit.note,
    }).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.toasts.success('Vulnérabilité mise à jour', `${updated.id} → ${this.statusLabel(updated.status)}`);
        this.selected.set(updated);
        this.load();
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error('Mise à jour impossible', errorMessage(e));
      },
    });
  }

  /** « Prendre » : passe la vulnérabilité en cours de traitement et l'assigne à l'utilisateur connecté. */
  take(v: Vulnerability): void {
    const me = this.auth.user()?.username;
    this.api.updateVulnerability(v.recordId, { status: 'IN_PROGRESS', assignee: v.assignee || me }).subscribe({
      next: (updated) => {
        this.toasts.success('Prise en charge', `${updated.id} est maintenant « en cours » et vous est assignée.`);
        if (this.selected()?.recordId === updated.recordId) this.select(updated);
        this.load();
      },
      error: (e) => this.toasts.error('Mise à jour impossible', errorMessage(e)),
    });
  }

  scan(kind: ScanKind): void {
    if (this.isRunning(kind)) return;
    const call: Observable<Job> = kind === 'deps' ? this.api.scanDependencies()
      : kind === 'image' ? this.api.scanImage(this.image.trim() || undefined) : this.api.syncSonar();
    call.subscribe({
      next: (job) => {
        this.toasts.info(job.label, 'Lancé en arrière-plan : vous pouvez continuer à travailler.');
        this.follow(kind, job);
      },
      error: (e) => this.toasts.error('Lancement impossible', errorMessage(e)),
    });
  }

  importReport(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.api.importSecurityReport(this.importType, file).subscribe({
      next: (d) => {
        const message = `${d.detected} détectée(s) · ${d.newCount} nouvelle(s) · ${d.resolvedCount} corrigée(s) · ${d.reopenedCount} rouverte(s)`;
        this.toasts.success(`Rapport ${SOURCE_LABELS[d.source] ?? d.source} importé`, message);
        this.lastResults.update((r) => ({ ...r, import: { ok: true, message: `${file.name} : ${message}` } }));
        this.load();
      },
      error: (e) => {
        this.lastResults.update((r) => ({ ...r, import: { ok: false, message: errorMessage(e) } }));
        this.toasts.error('Import impossible', errorMessage(e));
      },
    });
    input.value = '';
  }

  exportCsv(): void {
    this.api.download('reports/vulnerabilities.csv').subscribe({
      next: (blob) => saveBlob(blob, `vulnerabilites-${new Date().toISOString().slice(0, 10)}.csv`),
      error: (e) => this.toasts.error('Export impossible', errorMessage(e)),
    });
  }

  explainVuln(v: Vulnerability): void {
    this.explain.set({
      incidentType: 'vulnerability',
      source: v.affectedComponent,
      message: `${v.id} (${v.severity}${v.cvssScore ? ', CVSS ' + v.cvssScore : ''}) : ${v.description}`.slice(0, 3000),
      extraContext: `Détectée par ${v.source}.${v.fixedVersion ? ' Corrigée en ' + v.fixedVersion + '.' : ''}`,
      incidentRef: `vuln:${v.source}:${v.id}:${v.affectedComponent}`.slice(0, 250),
    });
  }

  isRunning(kind: string): boolean {
    return this.runningJobs()[kind] === true;
  }

  toolOk(key: string): boolean {
    const t = this.tools();
    return !t || t[key]?.available !== false;
  }

  sourceLabel(source: string): string {
    return SOURCE_LABELS[source] ?? source;
  }

  severityLabel(severity: string): string {
    return SEVERITY_LABELS[severity] ?? severity;
  }

  /** Action recommandée, affichée dans le panneau de détail. */
  advice(v: Vulnerability): string {
    if (v.status === 'FIXED') {
      return v.autoResolved ? 'Le dernier scan ne la détecte plus : elle a été clôturée automatiquement.'
        : 'Marquée corrigée. Si un prochain scan la détecte à nouveau, elle sera rouverte comme régression.';
    }
    if (v.status === 'ACCEPTED_RISK' || v.status === 'FALSE_POSITIVE') {
      return 'Écartée du décompte des vulnérabilités ouvertes. Justifiez la décision dans la note pour la traçabilité.';
    }
    if (v.fixedVersion) {
      return `Mettez à jour le composant vers la version ${v.fixedVersion} (ou supérieure), puis relancez le scan : la vulnérabilité passera en « corrigée » automatiquement.`;
    }
    if (v.source === 'sonarqube') {
      return 'Corrigez le code signalé, relancez l\'analyse SonarQube puis « Synchroniser SonarQube ».';
    }
    return 'Aucune version corrigée connue : demandez une analyse IA pour des mesures de contournement, ou acceptez le risque en le justifiant.';
  }

  private statusLabel(status: string): string {
    return ({ NEW: 'nouvelle', IN_PROGRESS: 'en cours', FIXED: 'corrigée', ACCEPTED_RISK: 'risque accepté', FALSE_POSITIVE: 'faux positif' } as Record<string, string>)[status] ?? status;
  }

  /** Suit un scan jusqu'à sa fin, mémorise son résultat et recharge la liste. */
  private follow(kind: ScanKind, job: Job): void {
    this.runningJobs.update((r) => ({ ...r, [kind]: true }));
    this.api.waitForJob(job.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (done) => {
        this.runningJobs.update((r) => ({ ...r, [kind]: false }));
        const ok = done.status === 'SUCCEEDED';
        this.lastResults.update((r) => ({ ...r, [kind]: { ok, message: done.message ?? (ok ? 'Terminé.' : 'Échec sans détail.') } }));
        // La notification temps réel annonce déjà la fin du scan ; on ne double le message que si elle est coupée.
        if (!this.events.connected()) {
          if (ok) this.toasts.success(done.label, done.message ?? undefined);
          else this.toasts.error(done.label, 'Le détail est affiché dans le panneau « Lancer une analyse ».');
        }
        this.load();
      },
      error: (e) => {
        this.runningJobs.update((r) => ({ ...r, [kind]: false }));
        this.lastResults.update((r) => ({ ...r, [kind]: { ok: false, message: `Suivi du scan interrompu : ${errorMessage(e)}` } }));
      },
    });
  }
}
