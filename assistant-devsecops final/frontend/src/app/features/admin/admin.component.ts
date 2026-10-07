import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { AuditEntry, Role, UserUpsert, UserView } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { SHARED_PIPES } from '../../shared/pipes';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [FormsModule, ...SHARED_PIPES],
  templateUrl: './admin.component.html',
})
export class AdminComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  readonly auth = inject(AuthService);

  readonly tab = signal<'users' | 'audit'>('users');
  readonly users = signal<UserView[]>([]);
  readonly audit = signal<AuditEntry[]>([]);
  readonly editing = signal<UserView | null>(null);
  readonly formOpen = signal(false);
  auditFilter = '';
  readonly roles: Role[] = ['DEVELOPER', 'QUALITY_MANAGER', 'ADMIN'];
  form: UserUpsert = {};

  ngOnInit(): void {
    if (this.auth.isAdmin()) {
      this.loadUsers();
    } else {
      this.tab.set('audit');
    }
    this.loadAudit();
  }

  loadUsers(): void {
    this.api.users().subscribe({ next: (u) => this.users.set(u), error: (e) => this.toasts.error('Utilisateurs indisponibles', errorMessage(e)) });
  }

  loadAudit(): void {
    this.api.audit(300).subscribe({ next: (a) => this.audit.set(a), error: (e) => this.toasts.error('Audit indisponible', errorMessage(e)) });
  }

  filteredAudit(): AuditEntry[] {
    const q = this.auditFilter.trim().toLowerCase();
    if (!q) return this.audit();
    return this.audit().filter((a) => [a.username, a.action, a.target, a.details].some((v) => (v ?? '').toLowerCase().includes(q)));
  }

  create(): void {
    this.editing.set(null);
    this.form = { role: 'DEVELOPER', enabled: true };
    this.formOpen.set(true);
  }

  edit(u: UserView): void {
    this.editing.set(u);
    this.form = { fullName: u.fullName ?? '', email: u.email ?? '', role: u.role, enabled: u.enabled, password: '' };
    this.formOpen.set(true);
  }

  save(): void {
    const current = this.editing();
    const payload: UserUpsert = { ...this.form };
    if (current && !payload.password) delete payload.password;
    const call = current ? this.api.updateUser(current.id, payload) : this.api.createUser(payload);
    call.subscribe({
      next: (u) => {
        this.toasts.success(current ? 'Utilisateur mis à jour' : 'Utilisateur créé', u.username);
        this.formOpen.set(false);
        this.loadUsers();
        this.loadAudit();
      },
      error: (e) => this.toasts.error('Enregistrement impossible', errorMessage(e)),
    });
  }

  toggleEnabled(u: UserView): void {
    this.api.updateUser(u.id, { enabled: !u.enabled }).subscribe({
      next: () => this.loadUsers(),
      error: (e) => this.toasts.error('Modification impossible', errorMessage(e)),
    });
  }

  remove(u: UserView): void {
    if (!confirm(`Supprimer définitivement le compte « ${u.username} » ?`)) return;
    this.api.deleteUser(u.id).subscribe({
      next: () => {
        this.toasts.success('Utilisateur supprimé');
        this.loadUsers();
      },
      error: (e) => this.toasts.error('Suppression impossible', errorMessage(e)),
    });
  }
}
