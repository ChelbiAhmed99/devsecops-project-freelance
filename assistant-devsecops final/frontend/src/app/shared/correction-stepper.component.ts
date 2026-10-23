import { Component, computed, input } from '@angular/core';
import { AiRecommendation } from '../core/models';

interface Step {
  label: string;
  state: 'done' | 'current' | 'todo' | 'failed';
  detail?: string;
}

/**
 * Cycle de vie d'une correction assistée par IA, sous contrôle humain :
 * détection → analyse d'origine → proposition → explication → validation humaine → application → vérification.
 */
@Component({
  selector: 'app-correction-stepper',
  standalone: true,
  template: `
    <ol class="stepper" aria-label="Cycle de vie de la correction">
      @for (s of steps(); track s.label) {
        <li class="stepper__step stepper__step--{{ s.state }}" [title]="s.detail ?? ''">
          <span class="stepper__dot">{{ s.state === 'done' ? '✓' : s.state === 'failed' ? '✗' : '' }}</span>
          <span class="stepper__label">{{ s.label }}</span>
        </li>
      }
    </ol>
  `,
})
export class CorrectionStepperComponent {
  readonly rec = input.required<AiRecommendation>();

  readonly steps = computed<Step[]>(() => {
    const r = this.rec();
    const reviewed = r.status !== 'PENDING_REVIEW';
    const approved = r.status === 'APPROVED';
    const fix = r.fixStatus ?? 'NONE';
    const applied = approved && fix !== 'NONE';
    return [
      { label: 'Détectée', state: 'done' },
      { label: 'Origine analysée', state: r.diagnostic ? 'done' : 'todo', detail: r.diagnostic ?? 'Pas d\'incident corrélé trouvé' },
      { label: 'Correction proposée', state: 'done' },
      { label: 'Expliquée', state: 'done' },
      {
        label: 'Validation humaine',
        state: !reviewed ? 'current' : approved ? 'done' : 'failed',
        detail: reviewed ? `${r.status} par ${r.reviewedBy ?? '?'}` : 'En attente',
      },
      { label: 'Appliquée', state: applied ? 'done' : approved ? 'current' : 'todo', detail: r.appliedReference ?? undefined },
      {
        label: 'Vérifiée',
        state: fix === 'VERIFIED' ? 'done' : fix === 'STILL_PRESENT' ? 'failed' : applied ? 'current' : 'todo',
        detail: r.verificationDetails ?? undefined,
      },
    ];
  });
}
