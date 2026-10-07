/** Petites fonctions de présentation partagées par les écrans. */

export function severityClass(value: string | null | undefined): string {
  switch ((value ?? '').toUpperCase()) {
    case 'CRITICAL':
    case 'FATAL':
      return 'critical';
    case 'HIGH':
    case 'ERROR':
      return 'high';
    case 'MEDIUM':
    case 'WARN':
      return 'medium';
    default:
      return 'low';
  }
}

export const STATUS_LABELS: Record<string, string> = {
  NEW: 'Nouvelle',
  IN_PROGRESS: 'En cours',
  FIXED: 'Corrigée',
  ACCEPTED_RISK: 'Risque accepté',
  FALSE_POSITIVE: 'Faux positif',
  PENDING_REVIEW: 'À valider',
  APPROVED: 'Validée',
  REJECTED: 'Rejetée',
  RUNNING: 'En cours',
  SUCCEEDED: 'Terminé',
  FAILED: 'Échec',
  PASSED: 'Réussi',
  ERROR: 'Erreur',
  SKIPPED: 'Ignoré',
};

export const ROLE_LABELS: Record<string, string> = {
  DEVELOPER: 'Développeur',
  QUALITY_MANAGER: 'Responsable qualité / sécurité',
  ADMIN: 'Administrateur',
};

export const CATEGORY_LABELS: Record<string, string> = {
  SECURITY: 'Sécurité',
  DATABASE: 'Base de données',
  RESOURCE: 'Ressources',
  NETWORK: 'Réseau',
  CODE_DEFECT: 'Défaut de code',
  CONFIGURATION: 'Configuration',
  PERFORMANCE: 'Performance',
  OTHER: 'Autre',
};

export function label(map: Record<string, string>, key: string | null | undefined): string {
  return key ? (map[key] ?? key) : '';
}

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const minutes = Math.round(diff / 60000);
  if (Math.abs(minutes) < 1) return "à l'instant";
  if (minutes < 60) return `il y a ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `il y a ${hours} h`;
  return `il y a ${Math.round(hours / 24)} j`;
}

export function shortTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

export function shortDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}
