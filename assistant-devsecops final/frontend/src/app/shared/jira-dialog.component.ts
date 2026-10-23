import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../core/api.service';
import { JiraIncidentQuery, JiraMatch, JiraProposal, JiraStatus } from '../core/models';
import { ToastService } from '../core/toast.service';
import { SHARED_PIPES } from './pipes';

/**
 * Rapprochement d'un incident avec Jira et proposition d'action CONTRÔLÉE :
 * 1. tickets existants correspondants (score + raison) ;
 * 2. brouillon pré-rempli (diagnostic + correction IA) que l'utilisateur relit et modifie ;
 * 3. proposition enregistrée « À approuver » : rien n'est écrit dans Jira avant l'approbation
 *    d'un responsable qualité (écran Assistant IA › Actions Jira).
 */
@Component({
  selector: 'app-jira-dialog',
  standalone: true,
  imports: [FormsModule, ...SHARED_PIPES],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="jira-title" (click)="$event.stopPropagation()">
        <header class="modal__header">
          <div>
            <p class="eyebrow">Jira · {{ status()?.mode === 'LIVE' ? 'connecté (' + status()?.deployment + ')' : 'mode simulation' }}</p>
            <h2 id="jira-title">Tickets liés à l'incident</h2>
          </div>
          <button class="icon-btn" type="button" aria-label="Fermer" (click)="closed.emit()">×</button>
        </header>
        <pre class="code-block">{{ query().text || query().incidentRef }}</pre>
        @if (status()?.mode === 'SIMULATION') {
          <div class="alert alert--info">Jira n'est pas configuré : le workflow complet (proposition, approbation, trace) fonctionne,
            mais l'exécution est simulée (aucun appel à Jira). Configurez JIRA_BASE_URL et JIRA_API_TOKEN pour l'activer.</div>
        }

        @if (draft(); as d) {
          <h3>{{ d.type === 'CREATE_ISSUE' ? 'Nouveau ticket' : 'Commentaire sur ' + d.issueKey }}</h3>
          @if (d.type === 'CREATE_ISSUE') {
            <label class="field"><span>Projet Jira</span><input type="text" name="project" [(ngModel)]="d.projectKey" /></label>
            <label class="field"><span>Résumé</span><input type="text" name="summary" [(ngModel)]="d.summary" maxlength="250" /></label>
            <label class="field"><span>Description (relisez et ajustez avant de proposer)</span>
              <textarea name="description" rows="12" [(ngModel)]="d.description"></textarea></label>
          } @else {
            <label class="field"><span>Commentaire</span><textarea name="comment" rows="8" [(ngModel)]="d.comment"></textarea></label>
          }
          <p class="disclaimer">La proposition sera enregistrée « À approuver ». Un responsable qualité
            @if (status()?.fourEyes) { <strong>différent de vous</strong> } devra l'approuver avant toute écriture dans Jira. Chaque étape est tracée dans le journal d'audit.</p>
          <div class="actions">
            <button class="btn btn--primary" type="button" [disabled]="saving()" (click)="propose()">Proposer</button>
            <button class="btn" type="button" (click)="draft.set(null)">Retour</button>
          </div>
        } @else {
          <h3>Tickets correspondants</h3>
          @if (loading()) {
            <div class="loading"><span class="spinner"></span> Recherche…</div>
          } @else {
            @for (m of matches(); track m.issue.key) {
              <div class="list-item">
                <div class="list-item__main">
                  <span><strong>{{ m.issue.key }}</strong> {{ m.issue.summary }}</span>
                  <span class="muted">{{ m.issue.status }} · correspondance {{ (m.score * 100).toFixed(0) }} % · {{ m.reason }}</span>
                </div>
                <div class="actions">
                  @if (m.issue.url) { <a class="btn btn--sm" [href]="m.issue.url" target="_blank" rel="noopener">Ouvrir</a> }
                  <button class="btn btn--sm" type="button" (click)="prepare('ADD_COMMENT', m.issue.key)">Commenter</button>
                </div>
              </div>
            } @empty {
              <p class="empty">Aucun ticket correspondant trouvé.</p>
            }
          }
          <div class="actions">
            <button class="btn btn--primary" type="button" (click)="prepare('CREATE_ISSUE')">Proposer un nouveau ticket</button>
          </div>
        }
      </div>
    </div>
  `,
})
export class JiraDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly query = input.required<JiraIncidentQuery>();
  readonly closed = output<void>();

  readonly status = signal<JiraStatus | null>(null);
  readonly matches = signal<JiraMatch[]>([]);
  readonly loading = signal(true);
  readonly draft = signal<JiraProposal | null>(null);
  readonly saving = signal(false);

  ngOnInit(): void {
    this.api.jiraStatus().subscribe({ next: (s) => this.status.set(s), error: () => this.status.set(null) });
    this.api.jiraMatches(this.query()).subscribe({
      next: (m) => {
        this.matches.set(m);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        this.toasts.error('Recherche Jira impossible', errorMessage(e));
      },
    });
  }

  prepare(type: 'CREATE_ISSUE' | 'ADD_COMMENT', issueKey?: string): void {
    this.api.jiraDraft(this.query(), type, issueKey).subscribe({
      next: (d) => this.draft.set({ ...d }),
      error: (e) => this.toasts.error('Brouillon impossible', errorMessage(e)),
    });
  }

  propose(): void {
    const d = this.draft();
    if (!d) return;
    this.saving.set(true);
    this.api.jiraPropose(d).subscribe({
      next: (a) => {
        this.saving.set(false);
        this.toasts.success('Proposition Jira enregistrée', `Action #${a.id} en attente d'approbation par un responsable qualité.`);
        this.closed.emit();
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error('Proposition refusée', errorMessage(e));
      },
    });
  }
}
