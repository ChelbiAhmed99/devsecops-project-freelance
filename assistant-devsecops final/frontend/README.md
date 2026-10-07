# Frontend — Assistant DevSecOps (Angular 22)

Application standalone (signals, nouveau control flow `@if/@for`), sans dépendance UI externe :
les graphiques sont des composants SVG maison (`src/app/shared/charts.ts`).

```bash
npm install
npm start            # http://localhost:4200 — /api est proxifié vers http://localhost:8080 (proxy.conf.json)
npm test             # tests unitaires (Vitest)
npm run build        # dist/frontend/browser
```

Structure :

| Dossier | Contenu |
|---|---|
| `core/` | modèles, `ApiService`, authentification JWT (service, intercepteur, guards), SSE, toasts |
| `shared/` | graphiques SVG, pipes, fenêtre d'explication IA, conteneur de notifications |
| `features/` | écrans : login, tableau de bord, logs, tests, sécurité, assistant IA, administration |

En production, l'image Docker sert l'application via nginx, qui relaie `/api` vers le backend
(y compris le flux temps réel `/api/events/stream`, sans mise en tampon).
