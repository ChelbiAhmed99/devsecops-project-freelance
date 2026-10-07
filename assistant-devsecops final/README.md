# Assistant DevSecOps

Plateforme qui centralise les **logs**, les **tests automatisés** et les **vulnérabilités** d'un projet, avec un
assistant IA qui explique les incidents et propose des corrections.

| Composant | Technologie | Rôle |
|---|---|---|
| `frontend/` | Angular + nginx | Interface web |
| `backend/` | Spring Boot (Java 17) | API REST, orchestration, base de données |
| `ia-service/` | FastAPI (Python) | Explications IA, copilote, machine learning |
| `samples/` | fichiers d'exemple | Rapports et logs prêts à importer pour essayer chaque écran |

Le mode d'emploi détaillé de chaque écran et les scénarios de test sont dans **[GUIDE.md](GUIDE.md)**.

## Démarrage avec Docker (recommandé)

Prérequis : Docker Desktop (ou Docker Engine + Compose) et un accès Internet pour le premier build.

```bash
cp .env.example .env        # puis renseigner au minimum JWT_SECRET (openssl rand -base64 48)
docker compose up -d --build
```

Ouvrir <http://localhost:4200>. Le premier build prend plusieurs minutes.

| Compte | Mot de passe initial | Rôle |
|---|---|---|
| `dev` | `Dev@2026` | Développeur |
| `qualite` | `Qualite@2026` | Responsable qualité / sécurité |
| `admin` | `Admin@2026` | Administrateur |

Ces comptes ne sont créés que si la base est vide ; changez leurs mots de passe ensuite.

Options :

```bash
docker compose --profile sonar up -d            # + SonarQube  (http://localhost:9000)
docker compose --profile observability up -d    # + Loki, Promtail, Grafana (http://localhost:3000)
```

Sous Docker Desktop (Windows, macOS), mettre `DOCKER_GID=0` dans `.env` pour que Trivy puisse scanner les images locales.

## Démarrage en développement (sans Docker)

Trois terminaux, dans cet ordre :

```bash
# 1. Service IA  (Python 3.11+)
cd ia-service
python -m venv .venv && . .venv/bin/activate      # Windows : .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --port 8000 --env-file .env   # --env-file charge GROQ_API_KEY ; sans clé : moteur local

# 2. Backend  (Java 17+, Maven 3.9+) : base H2 créée dans backend/data
cd backend
mvn spring-boot:run

# 3. Frontend  (Node 22+)
cd frontend
npm install
npm start                                           # http://localhost:4200
```

En développement, le bouton « Lancer les tests » exécute `mvn test` sur le backend lui-même : Maven doit être dans le
`PATH`. Si la connexion échoue avec les comptes ci-dessus, la base locale contient d'anciens comptes : arrêter le
backend, supprimer `backend/data/` et relancer.

## Vérifier que tout fonctionne

1. <http://localhost:4200> affiche la page de connexion ; se connecter avec `qualite`.
2. Le tableau de bord affiche un score de santé et, dans « Services », Backend, Base de données et Service IA en vert.
3. Écran **Tests** : la section d'aide liste l'état de Maven et du projet analysé.
4. Écran **Sécurité** : « Prérequis des scans » liste l'état de Maven, Trivy, Docker, SonarQube.
5. Dérouler les scénarios de [GUIDE.md](GUIDE.md) avec les fichiers de `samples/`.

API documentée (Swagger) : <http://localhost:8080/swagger-ui.html>.

## Tests automatisés du projet lui-même

```bash
cd backend && mvn verify                                        # JUnit + couverture JaCoCo
cd ia-service && pip install -r requirements-dev.txt && pytest
cd frontend && npm test && npm run build
```

## Sécurité des secrets

`.env` contient des secrets (clé JWT, clé du fournisseur IA, mots de passe) : il est ignoré par Git et ne doit pas être
envoyé par e-mail ni joint à une archive. Une clé qui a circulé doit être révoquée chez le fournisseur et remplacée.
