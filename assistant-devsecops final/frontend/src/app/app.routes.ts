import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    loadComponent: () => import('./features/shell/shell.component').then((m) => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        title: 'Tableau de bord',
        loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
      },
      {
        path: 'logs',
        title: 'Logs',
        loadComponent: () => import('./features/logs/logs.component').then((m) => m.LogsComponent),
      },
      {
        path: 'tests',
        title: 'Tests',
        loadComponent: () => import('./features/tests/tests.component').then((m) => m.TestsComponent),
      },
      {
        path: 'security',
        title: 'Sécurité',
        loadComponent: () => import('./features/security/security.component').then((m) => m.SecurityComponent),
      },
      {
        path: 'ai',
        title: 'Assistant IA',
        loadComponent: () => import('./features/ai/ai.component').then((m) => m.AiComponent),
      },
      {
        path: 'admin',
        title: 'Administration',
        canActivate: [roleGuard(['ADMIN', 'QUALITY_MANAGER'])],
        loadComponent: () => import('./features/admin/admin.component').then((m) => m.AdminComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
