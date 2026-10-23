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
  // Validation multi-applications
  VALIDATED: 'Validée',
  VALIDATED_WITH_RESERVES: 'Validée avec réserves',
  INCONCLUSIVE: 'Non concluante',
  PENDING: 'En attente',
  DONE: 'Terminée',
  COMPLETED: 'Terminée',
  PARTIAL: 'Partielle',
  CONFIRMED: 'Confirmée',
  OVERRIDDEN: 'Corrigée',
  // Actions Jira
  PROPOSED: 'À approuver',
  EXECUTED: 'Exécutée',
  SIMULATED: 'Simulée',
};

/** Statut de validation d'une application : libellé court + classe de couleur. */
export const VERDICT_LABELS: Record<string, string> = {
  VALIDATED: 'Validée',
  VALIDATED_WITH_RESERVES: 'Avec réserves',
  REJECTED: 'Rejetée',
  INCONCLUSIVE: 'Non concluante',
};

export function verdictClass(verdict: string | null | undefined): string {
  switch (verdict) {
    case 'VALIDATED':
      return 'ok';
    case 'VALIDATED_WITH_RESERVES':
      return 'high';
    case 'REJECTED':
      return 'critical';
    default:
      return 'low';
  }
}

export const LAYER_LABELS: Record<string, string> = {
  APPLICATION: 'Applicatif',
  SERVER: 'Serveur JBoss/WildFly',
  CICD: 'Pipeline CI/CD',
};

export const CRITICALITY_LABELS: Record<string, string> = {
  LOW: 'Faible',
  MEDIUM: 'Moyenne',
  HIGH: 'Haute',
  CRITICAL: 'Critique',
};

export const FIX_STATUS_LABELS: Record<string, string> = {
  NONE: 'Non appliquée',
  APPLIED: 'Appliquée — en attente de vérification',
  VERIFIED: 'Vérifiée : problème résolu',
  STILL_PRESENT: 'Inefficace : problème toujours présent',
  NOT_ENOUGH_DATA: 'En attente de nouvelles données',
};

export const CRITERION_STATUS_LABELS: Record<string, string> = {
  PASS: 'Conforme',
  WARN: 'Sous le seuil',
  FAIL: 'Échec',
  NO_DATA: 'Sans données',
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
  DEPLOYMENT: 'Déploiement',
  BUILD: 'Build / CI',
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
