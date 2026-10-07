import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  username = '';
  password = '';
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);

  readonly demoAccounts = [
    { username: 'dev', password: 'Dev@2026', label: 'Développeur' },
    { username: 'qualite', password: 'Qualite@2026', label: 'Responsable qualité / sécurité' },
    { username: 'admin', password: 'Admin@2026', label: 'Administrateur' },
  ];

  constructor() {
    if (this.auth.token()) {
      this.router.navigate(['/dashboard']);
    }
  }

  fill(account: { username: string; password: string }): void {
    this.username = account.username;
    this.password = account.password;
  }

  submit(): void {
    if (!this.username.trim() || !this.password) {
      this.error.set('Saisissez votre identifiant et votre mot de passe.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: (e) => {
        this.loading.set(false);
        this.error.set(e?.status === 401 ? 'Identifiants invalides.' : errorMessage(e));
      },
    });
  }
}
