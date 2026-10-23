import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { ServerEvent } from './models';
import { ToastService } from './toast.service';

const EVENT_TYPES = ['anomaly.critical', 'vulnerability.critical', 'job.finished', 'tests.completed', 'logs.ingested',
  'validation.application', 'validation.completed', 'validation.decision', 'correction.verified', 'jira.proposed'];

/**
 * Flux temps réel (Server-Sent Events) : notifications de nouvelles anomalies
 * critiques, vulnérabilités critiques, fin de scans/tests. Reconnexion
 * automatique gérée par EventSource ; le jeton est passé en paramètre car
 * EventSource ne permet pas d'en-tête Authorization.
 */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private readonly auth = inject(AuthService);
  private readonly toasts = inject(ToastService);
  private readonly zone = inject(NgZone);
  private source: EventSource | null = null;

  readonly events$ = new Subject<ServerEvent>();
  readonly notifications = signal<ServerEvent[]>([]);
  readonly unread = signal(0);
  readonly connected = signal(false);

  connect(): void {
    const token = this.auth.token();
    if (this.source || !token || typeof EventSource === 'undefined') return;
    this.source = new EventSource(`${environment.apiUrl}/events/stream?token=${encodeURIComponent(token)}`);
    this.source.onopen = () => this.zone.run(() => this.connected.set(true));
    this.source.onerror = () => this.zone.run(() => this.connected.set(false));
    for (const type of EVENT_TYPES) {
      this.source.addEventListener(type, (e) => this.zone.run(() => this.handle((e as MessageEvent).data)));
    }
  }

  disconnect(): void {
    this.source?.close();
    this.source = null;
    this.connected.set(false);
  }

  markAllRead(): void {
    this.unread.set(0);
  }

  private handle(raw: string): void {
    let event: ServerEvent;
    try {
      event = JSON.parse(raw) as ServerEvent;
    } catch {
      return;
    }
    this.events$.next(event);
    if (event.type === 'logs.ingested') return; // événement technique, pas de notification
    this.notifications.update((list) => [event, ...list].slice(0, 30));
    this.unread.update((n) => n + 1);
    const detail = typeof event.data['message'] === 'string' ? (event.data['message'] as string) : undefined;
    const kind = event.severity === 'critical' ? 'critical' : event.severity === 'warning' ? 'warning'
      : event.severity === 'success' ? 'success' : 'info';
    this.toasts.show(kind, event.title, detail);
  }
}
