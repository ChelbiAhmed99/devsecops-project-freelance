import { Component, computed, input } from '@angular/core';
import { verdictClass } from '../core/format';

/**
 * Score de validation (0-100) sous forme d'anneau coloré selon la décision.
 * Purement visuel : la valeur et la décision sont aussi données en texte (accessibilité).
 */
@Component({
  selector: 'app-score-ring',
  standalone: true,
  template: `
    <div class="score-ring score-ring--{{ tone() }}" [style.width.px]="size()" [style.height.px]="size()"
         role="img" [attr.aria-label]="'Score ' + label() + ' sur 100'">
      <svg viewBox="0 0 42 42">
        <circle cx="21" cy="21" r="15.915" fill="transparent" stroke="var(--border)" stroke-width="4" />
        <circle cx="21" cy="21" r="15.915" fill="transparent" class="score-ring__arc" stroke-width="4"
                stroke-linecap="round" [attr.stroke-dasharray]="dash()" stroke-dashoffset="25" />
      </svg>
      <span class="score-ring__value">{{ label() }}</span>
    </div>
  `,
})
export class ScoreRingComponent {
  readonly score = input<number | null>(null);
  readonly verdict = input<string | null>(null);
  readonly size = input(64);

  readonly tone = computed(() => (this.score() === null ? 'low' : verdictClass(this.verdict())));
  readonly label = computed(() => {
    const s = this.score();
    return s === null ? '—' : String(Math.round(s));
  });
  readonly dash = computed(() => {
    const s = Math.max(0, Math.min(100, this.score() ?? 0));
    return `${s} ${100 - s}`;
  });
}
