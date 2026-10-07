import { Component, inject } from '@angular/core';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-toast-container',
  standalone: true,
  template: `
    <div class="toasts" aria-live="polite">
      @for (t of toasts.toasts(); track t.id) {
        <div class="toast toast--{{ t.kind }}" role="status">
          <div class="toast__body">
            <strong>{{ t.title }}</strong>
            @if (t.message) { <span>{{ t.message }}</span> }
          </div>
          <button class="toast__close" type="button" aria-label="Fermer" (click)="toasts.dismiss(t.id)">×</button>
        </div>
      }
    </div>
  `,
})
export class ToastContainerComponent {
  readonly toasts = inject(ToastService);
}
