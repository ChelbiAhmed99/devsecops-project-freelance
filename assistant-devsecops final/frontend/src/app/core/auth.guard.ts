import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Role } from './models';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.token() !== null ? true : inject(Router).createUrlTree(['/login']);
};

/** Restreint une route à certains rôles (le backend applique de toute façon les mêmes règles). */
export function roleGuard(roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    return auth.hasRole(...roles) ? true : inject(Router).createUrlTree(['/dashboard']);
  };
}
