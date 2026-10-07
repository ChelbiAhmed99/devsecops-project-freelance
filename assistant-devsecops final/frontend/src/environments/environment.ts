/**
 * URL relative : en développement le proxy Angular (proxy.conf.json) et en
 * production nginx redirigent /api vers le backend. Aucune URL codée en dur,
 * aucun problème CORS, même image quel que soit l'environnement.
 */
export const environment = {
  production: false,
  apiUrl: '/api',
};
