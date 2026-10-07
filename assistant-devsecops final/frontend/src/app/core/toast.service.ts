import { Injectable, signal } from '@angular/core';

export type ToastKind = 'info' | 'success' | 'warning' | 'error' | 'critical';

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
}

/** Notifications éphémères affichées en bas à droite. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<Toast[]>([]);

  show(kind: ToastKind, title: string, message?: string, durationMs = 5000): void {
    const toast: Toast = { id: ++this.seq, kind, title, message };
    this.toasts.update((list) => [...list.slice(-4), toast]);
    setTimeout(() => this.dismiss(toast.id), kind === 'critical' ? durationMs * 2 : durationMs);
  }

  success(title: string, message?: string): void {
    this.show('success', title, message);
  }

  error(title: string, message?: string): void {
    this.show('error', title, message, 8000);
  }

  info(title: string, message?: string): void {
    this.show('info', title, message);
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
