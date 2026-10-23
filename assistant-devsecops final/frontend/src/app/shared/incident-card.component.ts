import { Component, input, output, signal } from '@angular/core';
import { CorrelatedIncident } from '../core/models';
import { SHARED_PIPES } from './pipes';

/**
 * Incident corrélé et son diagnostic explicable : cause racine probable, confiance,
 * facteurs chiffrés (pourquoi ce signal ?), chaîne causale et tests impactés.
 */
@Component({
  selector: 'app-incident-card',
  standalone: true,
  imports: [...SHARED_PIPES],
  template: `
    @let i = incident();
    <article class="panel incident panel--{{ i.severity | sev }}">
      <header class="anomaly__header">
        <div>
          <span class="badge badge--{{ i.severity | sev }}">{{ i.severity }}</span>
          <span class="badge badge--outline">{{ i.category | lbl: 'category' }}</span>
          @for (l of i.layers; track l) { <span class="badge layer layer--{{ l }}">{{ l | lbl: 'layer' }}</span> }
          <span class="muted">{{ i.start | dt }} → {{ i.end | dt }} · {{ i.eventCount }} événement(s)</span>
        </div>
        <div class="actions">
          <button class="btn btn--sm" type="button" (click)="open.set(!open())">{{ open() ? 'Masquer' : 'Pourquoi ?' }}</button>
          @if (showActions()) {
            <button class="btn btn--sm" type="button" (click)="jira.emit(i)">Jira</button>
            <button class="btn btn--sm btn--primary" type="button" (click)="explain.emit(i)">Corriger (IA)</button>
          }
        </div>
      </header>
      <p class="root-cause">
        <span class="eyebrow">Cause racine probable · confiance {{ i.rootCause.confidencePercent }} %</span>
        <span class="mono">[{{ i.rootCause.signal.layer | lbl: 'layer' }}] {{ i.rootCause.signal.message }}</span>
      </p>
      <p class="muted">{{ i.rootCause.rationale }}</p>
      @if (i.impactedTests.length) {
        <p class="muted">Tests impactés : @for (t of i.impactedTests; track t) { <code>{{ t }}</code>&nbsp; }</p>
      }
      @if (open()) {
        <div class="factors">
          <p class="eyebrow">Facteurs du score de cause racine ({{ i.rootCause.score }})</p>
          @for (f of i.rootCause.factors; track f.code) {
            <div class="factor">
              <span>{{ f.label }}</span>
              <span class="factor__bar" [attr.aria-label]="f.label + ' ' + f.value"><i [style.width.%]="f.value * 100"></i></span>
              <span class="muted">+{{ f.contribution }} · {{ f.detail }}</span>
            </div>
          }
        </div>
        @if (i.chain.length) {
          <p class="eyebrow">Chaîne causale</p>
          <ol class="chain">
            @for (c of i.chain; track $index) {
              <li><span class="mono">{{ c.fromLabel }}</span> → <span class="mono">{{ c.toLabel }}</span>
                <span class="muted"> · {{ c.rule === 'TEMPORAL' ? 'succession temporelle (à confirmer)' : c.explanation }}</span></li>
            }
          </ol>
        }
        @if (i.alternatives.length) {
          <p class="eyebrow">Autres hypothèses</p>
          <ul class="chain">
            @for (a of i.alternatives; track a.signal.key) {
              <li><span class="mono">[{{ a.signal.layer | lbl: 'layer' }}] {{ a.signal.message }}</span> <span class="muted">· score {{ a.score }}</span></li>
            }
          </ul>
        }
      }
    </article>
  `,
})
export class IncidentCardComponent {
  readonly incident = input.required<CorrelatedIncident>();
  readonly showActions = input(true);
  readonly explain = output<CorrelatedIncident>();
  readonly jira = output<CorrelatedIncident>();
  readonly open = signal(false);
}
