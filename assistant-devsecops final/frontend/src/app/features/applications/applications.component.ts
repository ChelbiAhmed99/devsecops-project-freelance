import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ApplicationUpsert, ApplicationValidationSummary, ApplicationView, Criticality, QualityGateResult } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SHARED_PIPES } from '../../shared/pipes';
import { ScoreRingComponent } from '../../shared/score-ring.component';

const EMPTY: ApplicationUpsert = {
  code: '', name: '', description: '', owner: '', criticality: 'MEDIUM', serverType: 'SPRING_BOOT', projectPath: '',
  logSources: '', jiraProjectKey: '', repositoryUrl: '', policyOverrides: '', active: true,
};

/** Portefeuille d'applications : chaque application a ses propres tests, logs, vulnérabilités, validations et rapports. */
@Component({
  selector: 'app-applications',
  standalone: true,
  imports: [FormsModule, RouterLink, ScoreRingComponent, ...SHARED_PIPES],
  templateUrl: './applications.component.html',
})
export class ApplicationsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  readonly auth = inject(AuthService);

  readonly apps = signal<ApplicationView[]>([]);
  readonly lastValidation = signal<Record<string, ApplicationValidationSummary | null>>({});
  readonly showInactive = signal(false);
  readonly editing = signal<{ code: string | null; form: ApplicationUpsert } | null>(null);
  readonly gate = signal<{ code: string; result: QualityGateResult } | null>(null);
  readonly loading = signal(false);

  readonly criticalities: Criticality[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  readonly serverTypes = ['WILDFLY', 'JBOSS_EAP', 'SPRING_BOOT', 'TOMCAT', 'AUTRE'];

  readonly visible = computed(() => this.apps().filter((a) => this.showInactive() || a.active));

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.applications(true).subscribe({
      next: (a) => {
        this.apps.set(a);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        this.toasts.error('Applications indisponibles', errorMessage(e));
      },
    });
    this.api.portfolio().subscribe({
      next: (p) => {
        const map: Record<string, ApplicationValidationSummary | null> = {};
        p.entries.forEach((e) => (map[e.application.code] = e.lastValidation));
        this.lastValidation.set(map);
      },
      error: () => this.lastValidation.set({}),
    });
  }

  create(): void {
    this.editing.set({ code: null, form: { ...EMPTY } });
  }

  edit(a: ApplicationView): void {
    this.editing.set({
      code: a.code,
      form: {
        code: a.code, name: a.name, description: a.description ?? '', owner: a.owner ?? '', criticality: a.criticality,
        serverType: a.serverType ?? '', projectPath: a.projectPath ?? '', logSources: a.logSources ?? '',
        jiraProjectKey: a.jiraProjectKey ?? '', repositoryUrl: a.repositoryUrl ?? '', policyOverrides: a.policyOverrides ?? '',
        active: a.active,
      },
    });
  }

  save(): void {
    const e = this.editing();
    if (!e) return;
    const request = e.code ? this.api.updateApplication(e.code, e.form) : this.api.createApplication(e.form);
    request.subscribe({
      next: (a) => {
        this.toasts.success(e.code ? 'Application modifiée' : 'Application créée', a.name);
        this.editing.set(null);
        this.load();
      },
      error: (err) => this.toasts.error('Enregistrement impossible', errorMessage(err)),
    });
  }

  toggleActive(a: ApplicationView): void {
    if (a.active && !confirm(`Désactiver ${a.name} ? Son historique est conservé, mais elle ne sera plus proposée à la validation.`)) return;
    this.api.updateApplication(a.code, { active: !a.active }).subscribe({
      next: () => this.load(),
      error: (err) => this.toasts.error('Modification impossible', errorMessage(err)),
    });
  }

  showGate(a: ApplicationView): void {
    this.api.applicationQualityGate(a.code).subscribe({
      next: (result) => this.gate.set({ code: a.code, result }),
      error: (err) => this.toasts.error('Quality gate indisponible', errorMessage(err)),
    });
  }

  validate(a: ApplicationView): void {
    this.router.navigate(['/validation'], { queryParams: { apps: a.code } });
  }

  sources(a: ApplicationView): string[] {
    return (a.logSources ?? '').split(',').map((s) => s.trim()).filter((s) => s.length > 0);
  }
}
