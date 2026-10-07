import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { EventsService } from '../../core/events.service';
import { ServerEvent } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SHARED_PIPES } from '../../shared/pipes';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  roles?: string[];
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule, ...SHARED_PIPES],
  templateUrl: './shell.component.html',
})
export class ShellComponent implements OnInit, OnDestroy {
  readonly auth = inject(AuthService);
  readonly events = inject(EventsService);
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Tableau de bord', icon: 'M3 3h6v8H3zM11 3h6v5h-6zM11 10h6v7h-6zM3 13h6v4H3z' },
    { path: '/logs', label: 'Logs', icon: 'M3 5h14M3 10h10M3 15h12' },
    { path: '/tests', label: 'Tests', icon: 'M10 2.8a7.2 7.2 0 1 0 0 14.4a7.2 7.2 0 1 0 0-14.4M6.5 10l2.3 2.3l4.7-4.8' },
    { path: '/security', label: 'Sécurité', icon: 'M10 2.3l6.3 2.5v4.6c0 3.9-2.7 7-6.3 7.9c-3.6-.9-6.3-4-6.3-7.9V4.8z' },
    { path: '/ai', label: 'Assistant IA', icon: 'M10 2l1.7 6.3L18 10l-6.3 1.7L10 18l-1.7-6.3L2 10l6.3-1.7z' },
    { path: '/admin', label: 'Administration', icon: 'M10 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6M10 1.5v2.5M10 16v2.5M1.5 10H4M16 10h2.5M4 4l1.8 1.8M14.2 14.2L16 16M4 16l1.8-1.8M14.2 5.8L16 4', roles: ['ADMIN', 'QUALITY_MANAGER'] },
  ];

  readonly visibleNav = computed(() =>
    this.nav.filter((n) => !n.roles || n.roles.includes(this.auth.role() ?? '')));

  readonly todayLabel = new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  readonly notificationsOpen = signal(false);
  readonly passwordOpen = signal(false);
  readonly menuOpen = signal(false);
  currentPassword = '';
  newPassword = '';

  ngOnInit(): void {
    this.events.connect();
  }

  ngOnDestroy(): void {
    this.events.disconnect();
  }

  toggleNotifications(): void {
    this.notificationsOpen.update((v) => !v);
    this.events.markAllRead();
  }

  changePassword(): void {
    this.api.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.toasts.success('Mot de passe modifié');
        this.passwordOpen.set(false);
        this.currentPassword = this.newPassword = '';
      },
      error: (e) => this.toasts.error('Modification impossible', errorMessage(e)),
    });
  }

  /** Texte complémentaire d'une notification (message du job, composant touché…). */
  detail(n: ServerEvent): string {
    const d = n.data ?? {};
    const text = d['message'] ?? d['component'] ?? '';
    return typeof text === 'string' ? text.slice(0, 220) : '';
  }

  logout(): void {
    this.events.disconnect();
    this.auth.logout();
  }
}
