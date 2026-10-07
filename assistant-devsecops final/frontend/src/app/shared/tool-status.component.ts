import { Component, computed, input } from '@angular/core';
import { ToolStatus } from '../core/models';

/**
 * Affiche l'état des prérequis d'un traitement (Maven, Trivy, Docker…) : une
 * pastille par outil et, pour ceux qui manquent, la marche à suivre. L'objectif
 * est que l'utilisateur sache avant de cliquer ce qui va fonctionner.
 */
@Component({
  selector: 'app-tool-status',
  standalone: true,
  template: `
    @if (items().length) {
      <ul class="tool-status">
        @for (t of items(); track t.key) {
          <li class="tool-status__item" [class.tool-status__item--off]="!t.available">
            <span class="status-dot status-dot--{{ t.available ? 'up' : 'off' }}"></span>
            <span><strong>{{ t.label }}</strong> <span class="muted">· {{ t.detail }}</span></span>
            @if (!t.available && t.hint) { <p class="tool-status__hint">{{ t.hint }}</p> }
          </li>
        }
      </ul>
    }
  `,
})
export class ToolStatusComponent {
  /** Réponse de GET /api/system/tools (null tant qu'elle n'est pas chargée). */
  readonly tools = input<Record<string, ToolStatus> | null>(null);
  /** Outils à afficher, dans l'ordre. */
  readonly keys = input<string[]>([]);

  readonly items = computed(() => {
    const tools = this.tools();
    if (!tools) return [];
    return this.keys().filter((k) => !!tools[k]).map((k) => ({ key: k, ...tools[k] }));
  });
}
