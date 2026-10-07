import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { LoginResponse, Role, UserView } from './models';

const STORAGE_KEY = 'assistant.session';

interface Session {
  token: string;
  expiresAt: string;
  user: UserView;
}

/**
 * Authentification JWT réelle : le jeton renvoyé par /api/auth/login est
 * conservé en sessionStorage (effacé à la fermeture de l'onglet) et envoyé
 * par l'intercepteur HTTP. L'expiration est vérifiée côté client pour
 * rediriger proprement vers la page de connexion.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(this.restore());

  readonly user = computed(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);
  readonly role = computed<Role | null>(() => this.session()?.user.role ?? null);
  readonly canManageSecurity = computed(() => this.hasRole('QUALITY_MANAGER', 'ADMIN'));
  readonly isAdmin = computed(() => this.hasRole('ADMIN'));

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${environment.apiUrl}/auth/login`, { username, password }).pipe(
      tap((res) => {
        const session: Session = { token: res.token, expiresAt: res.expiresAt, user: res.user };
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
        this.session.set(session);
      }),
    );
  }

  logout(redirect = true): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
    if (redirect) {
      this.router.navigate(['/login']);
    }
  }

  token(): string | null {
    const s = this.session();
    if (!s) return null;
    if (this.isExpired(s)) {
      this.logout();
      return null;
    }
    return s.token;
  }

  hasRole(...roles: Role[]): boolean {
    const role = this.role();
    return role !== null && roles.includes(role);
  }

  private isExpired(s: Session): boolean {
    return new Date(s.expiresAt).getTime() <= Date.now();
  }

  private restore(): Session | null {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const s = JSON.parse(raw) as Session;
      return s.token && !this.isExpired(s) ? s : null;
    } catch {
      return null;
    }
  }
}
