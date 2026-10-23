import { Component, inject, input, output, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../core/api.service';
import { AiRecommendation, ExplainRequest } from '../core/models';
import { ToastService } from '../core/toast.service';
import { CorrectionStepperComponent } from './correction-stepper.component';
import { SHARED_PIPES } from './pipes';

/**
 * Fenêtre modale : demande (ou récupère depuis le cache) l'explication IA d'un
 * incident, l'affiche, et permet la validation humaine immédiate.
 */
@Component({
  selector: 'app-explain-dialog',
  standalone: true,
  imports: [FormsModule, CorrectionStepperComponent, ...SHARED_PIPES],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="explain-title" (click)="$event.stopPropagation()">
        <header class="modal__header">
          <div>
            <p class="eyebrow">Analyse IA · {{ request().incidentType }}</p>
            <h2 id="explain-title">{{ request().source || 'Incident' }}</h2>
          </div>
          <button class="icon-btn" type="button" aria-label="Fermer" (click)="closed.emit()">×</button>
        </header>
        <pre class="code-block">{{ request().message }}</pre>

        @if (loading()) {
          <div class="loading"><span class="spinner"></span> Analyse en cours…</div>
        } @else if (error()) {
          <div class="alert alert--error">{{ error() }}</div>
          <button class="btn" type="button" (click)="load(true)">Réessayer</button>
        } @else if (rec(); as r) {
          <div class="rec">
            <app-correction-stepper [rec]="r" />
            <div class="rec__meta">
              <span class="badge badge--{{ r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'critical' : 'medium' }}">{{ r.status | lbl }}</span>
              <span class="muted">{{ r.provider }} · {{ r.model }} · {{ r.createdAt | relative }}</span>
            </div>
            @if (r.diagnostic) {
              <h3>Analyse d'origine (corrélation déterministe)</h3>
              <p class="advice">{{ r.diagnostic }}</p>
            }
            <h3>Cause probable</h3>
            <p>{{ r.explanation }}</p>
            <h3>Correction proposée</h3>
            <pre class="rec__fix">{{ r.suggestedFix }}</pre>
            <h3>Gravité</h3>
            <p>{{ r.severityAssessment }}</p>
            @if (r.status === 'PENDING_REVIEW') {
              <label class="field">
                <span>Commentaire de validation (optionnel)</span>
                <input type="text" [(ngModel)]="comment" placeholder="ex. correctif appliqué dans la MR !42" />
              </label>
              <div class="actions">
                <button class="btn btn--primary" type="button" (click)="review('APPROVED')">Valider</button>
                <button class="btn" type="button" (click)="review('REJECTED')">Rejeter</button>
                <button class="btn btn--ghost" type="button" (click)="load(true)">Régénérer</button>
              </div>
            } @else {
              <p class="muted">Revue par {{ r.reviewedBy }} {{ r.reviewedAt | relative }}{{ r.reviewComment ? ' : « ' + r.reviewComment + ' »' : '' }}</p>
            }
            @if (r.status === 'APPROVED') {
              <h3>Application et vérification</h3>
              @if (!r.appliedAt) {
                <p class="muted">Appliquez la correction dans le code (l'assistant ne modifie jamais le code ni la production), puis déclarez-la ici :
                  l'assistant vérifiera sur les prochains logs, tests ou scans que le problème a disparu.</p>
                <div class="review-row">
                  <input type="text" [(ngModel)]="reference" placeholder="Référence : commit, merge request, ticket…" aria-label="Référence de la correction" />
                  <button class="btn btn--primary" type="button" [disabled]="!reference.trim()" (click)="markApplied()">Déclarer appliquée</button>
                </div>
              } @else {
                <p><span class="badge badge--{{ r.fixStatus === 'VERIFIED' ? 'ok' : r.fixStatus === 'STILL_PRESENT' ? 'critical' : 'medium' }}">{{ r.fixStatus | lbl: 'fix' }}</span>
                  <span class="muted">appliquée par {{ r.appliedBy }} {{ r.appliedAt | relative }} · réf. {{ r.appliedReference }}</span></p>
                @if (r.verificationDetails) { <p class="advice">{{ r.verificationDetails }}</p> }
                <button class="btn btn--sm" type="button" (click)="verify()">Vérifier maintenant</button>
              }
            }
            <p class="disclaimer">Recommandation générée automatiquement : elle doit être vérifiée par un humain avant toute application.</p>
          </div>
        }
      </div>
    </div>
  `,
})
export class ExplainDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly request = input.required<ExplainRequest>();
  readonly closed = output<void>();

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly rec = signal<AiRecommendation | null>(null);
  comment = '';
  reference = '';

  ngOnInit(): void {
    this.load(false);
  }

  load(force: boolean): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.explain({ ...this.request(), force }).subscribe({
      next: (r) => {
        this.rec.set(r);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  markApplied(): void {
    const r = this.rec();
    if (!r || !this.reference.trim()) return;
    this.api.markApplied(r.id, this.reference.trim()).subscribe({
      next: (updated) => {
        this.rec.set(updated);
        this.reference = '';
        this.toasts.success('Correction déclarée appliquée', 'Elle sera vérifiée automatiquement sur les nouvelles données.');
      },
      error: (e) => this.toasts.error('Déclaration impossible', errorMessage(e)),
    });
  }

  verify(): void {
    const r = this.rec();
    if (!r) return;
    this.api.verifyCorrection(r.id).subscribe({
      next: (updated) => this.rec.set(updated),
      error: (e) => this.toasts.error('Vérification impossible', errorMessage(e)),
    });
  }

  review(status: 'APPROVED' | 'REJECTED'): void {
    const r = this.rec();
    if (!r) return;
    this.api.reviewRecommendation(r.id, status, this.comment || undefined).subscribe({
      next: (updated) => {
        this.rec.set(updated);
        this.toasts.success(status === 'APPROVED' ? 'Recommandation validée' : 'Recommandation rejetée');
      },
      error: (e) => this.toasts.error('Échec de la validation', errorMessage(e)),
    });
  }
}
