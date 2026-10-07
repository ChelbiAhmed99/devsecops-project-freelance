cd backend
mvn spring-boot:run

cd ia-service
python -m venv venv
.venv\Scripts\activate  
pip install -r requirements-dev.txt
uvicorn app.main:app --reload --port 8000

```bash
# Liste des logs actuellement dans le store (préchargé avec sample-logs/app.log)
curl http://localhost:8080/api/logs

# Anomalies détectées (erreurs regroupées par fréquence)
curl http://localhost:8080/api/logs/anomalies

# Pousser un nouveau lot de logs (simule un pipeline CI/CD ou une appli qui log)
curl -X POST http://localhost:8080/api/logs/ingest \
  -H "Content-Type: text/plain" \
  --data-binary $'2026-09-20 09:00:00 ERROR [order-service] Payment gateway unreachable\n2026-09-20 09:01:00 ERROR [order-service] Payment gateway unreachable'

# Vider le store (utile pour repartir d'un état propre en démo)
curl -X DELETE http://localhost:8080/api/logs

# Déclencher l'exécution des tests (l'assistant se teste lui-même)
curl -X POST http://localhost:8080/api/tests/run

# Récupérer les résultats du dernier rapport de tests
curl http://localhost:8080/api/tests/results

# Pour chaque test en échec, voir les logs les plus pertinents
curl http://localhost:8080/api/tests/failures/correlated

# Déclencher le scan de vulnérabilités des dépendances
# ATTENTION : le tout premier lancement télécharge la base NVD, peut prendre
# plusieurs minutes. Les lancements suivants sont bien plus rapides.
curl -X POST http://localhost:8080/api/security/scan/dependencies

# Récupérer la liste des vulnérabilités trouvées (triée par gravité)
curl http://localhost:8080/api/security/vulnerabilities

# Marquer une vulnérabilité comme "en cours de traitement"
curl -X PATCH "http://localhost:8080/api/security/vulnerabilities/CVE-2024-XXXXX/status?component=exemple-lib-1.2.3.jar" \
  -H "Content-Type: application/json" \
  -d "{\"status\": \"IN_PROGRESS\"}"
```

### Configurer SonarQube (Sprint 7)

```bash
# Démarrer un serveur SonarQube local (première fois : peut prendre 1-2 min à être prêt)
docker run -d --name sonarqube -p 9000:9000 sonarqube:community

# Se connecter sur http://localhost:9000 (admin/admin par défaut, changement de
# mot de passe demandé au premier login), générer un token dans
# Mon compte > Sécurité, puis lancer l'analyse :
cd backend
mvn sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=VOTRE_TOKEN

# Une fois l'analyse terminée, l'assistant peut aller consulter les résultats.
# Donnez le token au backend (jamais en dur dans application.yml) :
#   set ASSISTANT_SECURITY_SONAR_TOKEN=VOTRE_TOKEN   (Windows cmd)
#   $env:ASSISTANT_SECURITY_SONAR_TOKEN="VOTRE_TOKEN" (PowerShell)
mvn spring-boot:run
curl http://localhost:8080/api/security/vulnerabilities
```

### Configurer Trivy (Sprint 7)

```bash
# Installer Trivy : https://aquasecurity.github.io/trivy/latest/getting-started/installation/
# Construire l'image de l'assistant pour avoir quelque chose à scanner :
docker build -t assistant-devsecops-backend:latest ./backend

# Déclencher le scan depuis l'assistant
curl -X POST http://localhost:8080/api/security/scan/images
```

### Utiliser la brique IA depuis le backend

```bash
# Le backend doit pouvoir joindre le service IA (par défaut http://localhost:8000,
# voir assistant.ai.service-url dans application.yml)
mvn spring-boot:run

# Demander une explication (crée une recommandation PENDING_REVIEW)
curl -X POST http://localhost:8080/api/ai/explain \
  -H "Content-Type: application/json" \
  -d "{\"incidentType\": \"log_anomaly\", \"source\": \"payment-service\", \"message\": \"Connection timeout to database after 30000ms\", \"occurrences\": 5}"

# Lister toutes les recommandations générées
curl http://localhost:8080/api/ai/recommendations

# Valider (ou rejeter) une recommandation - remplacez REC_ID par l'id reçu ci-dessus
curl -X PATCH http://localhost:8080/api/ai/recommendations/REC_ID/status \
  -H "Content-Type: application/json" \
  -d "{\"status\": \"APPROVED\"}"
```

### Lancer les tests unitaires

```bash
cd 
mvn test
```

## Comment lancer avec Docker

```bash
docker compose up --build
```

## Ce qui a été fait au Sprint 1 (et pourquoi)

- **Parsing de logs** (`LogParserService`) : lit un fichier de logs au format
  `yyyy-MM-dd HH:mm:ss LEVEL [source] message` et le transforme en objets
  `LogEntry` structurés. Le fichier statique (`sample-logs/app.log`) sert de
  jeu de test réaliste ; il sera remplacé par une vraie source (Loki,
  Elasticsearch, ou flux CI/CD) au sprint suivant, sans changer le reste du
  code — c'est tout l'intérêt de séparer le parsing du reste.

- **Détection d'anomalies par fréquence** (`LogAnalysisService`) : normalise
  les messages d'erreur (en remplaçant les nombres et UUID par des
  marqueurs génériques) pour regrouper les variantes d'une même erreur, puis
  compte les occurrences. Un message qui revient au moins `anomaly-threshold`
  fois (configurable dans `application.yml`, défaut = 3) est marqué comme
  anomalie critique. C'est une approche simple mais qui fonctionne bien
  comme baseline, avant d'introduire du clustering plus avancé.

- **API REST** (`LogController`) : deux endpoints (`/api/logs` et
  `/api/logs/anomalies`) prêts à être consommés par le futur frontend Angular.

## Ce qui a été fait au Sprint 2 (et pourquoi)

- **`LogStore`** : remplace la relecture systématique du fichier statique par
  un store en mémoire (`CopyOnWriteArrayList`, thread-safe) qui accumule les
  logs au fil du temps. Préchargé au démarrage avec `sample-logs/app.log`
  pour garder une démo immédiate, mais désormais alimentable en continu.
- **`POST /api/logs/ingest`** : point d'entrée qui simule ce que fera le vrai
  pipeline CI/CD au sprint "intégration CI/CD" — pousser ses logs vers
  l'assistant après chaque exécution. Accepte du texte brut, le parse avec
  le même `LogParserService` que le Sprint 1 (aucune duplication de logique).
- **`DELETE /api/logs`** : utilitaire de démo/tests pour repartir d'un état propre.

Pourquoi ce choix plutôt que Loki/Grafana directement : c'est un pas
intermédiaire qui apporte déjà la notion de flux continu (essentielle pour
le projet) sans ajouter tout de suite la complexité d'une stack de
centralisation externe. Loki peut être branché plus tard en alimentant ce
même `LogStore` via un job qui interroge son API — le reste du code ne
changerait pas.

## Ce qui a été fait au Sprint 3 (et pourquoi)

- **`TextSimilarityUtil`** : similarité de Jaccard sur les mots des messages,
  pour fusionner des erreurs proches mais pas identiques après normalisation
  (ex: "Unable to reach payment gateway" et "...payment provider").
  Approche simple et explicable, suffisante pour un PFE ; une amélioration
  possible (embeddings, TF-IDF) peut être mentionnée comme perspective dans
  le rapport si le temps manque pour l'implémenter.
- **`AnomalySummaryService`** : génère un résumé en langage naturel par
  groupe d'anomalies (nombre d'occurrences, services touchés, criticité).
  Volontairement à base de règles pour l'instant — c'est exactement
  l'interface que la brique IA (Sprint 8-9) viendra remplacer par un appel
  à un LLM, sans toucher au reste de la chaîne.
- **`AnomalyGroup` enrichi** : porte désormais `affectedSources` (quels
  services sont touchés) et `summary` (le résumé généré).
- Seuil de similarité configurable (`assistant.logs.similarity-threshold`,
  défaut 0.6) dans `application.yml`, sur le même principe que le seuil
  d'anomalie du Sprint 1.

## Ce qui a été fait au Sprint 4 (et pourquoi)

- **`TestExecutionService`** : déclenche `mvn test` comme processus externe
  sur un projet configurable (`assistant.tests.project-path`, défaut `.` —
  le backend lui-même pour la démo). Détecte l'OS pour utiliser `mvn.cmd`
  sous Windows. Timeout de sécurité (120s par défaut) pour ne jamais rester
  bloqué indéfiniment.
- **`SurefireReportParser`** : parse les fichiers XML générés par Maven dans
  `target/surefire-reports/`, sans dépendance externe (parseur XML du JDK).
  Extrait par suite : nombre de tests, échecs, erreurs, skip, et le détail
  par cas de test (nom, statut, message d'échec).
- **`TestController`** : `POST /api/tests/run` déclenche l'exécution,
  `GET /api/tests/results` renvoie la synthèse consolidée (taux de réussite
  global, détail par suite).
- Pourquoi l'assistant se teste lui-même en démo : ça donne un scénario de
  démonstration crédible sans avoir à préparer un projet tiers, et ça
  préfigure le principe d'auto-surveillance qui sera généralisé au sprint
  CI/CD.

## Ce qui a été fait au Sprint 5 (et pourquoi)

- **`TestExecutionService`** mémorise désormais la fenêtre temporelle
  (début/fin) de la dernière exécution de tests déclenchée.
- **`TestLogCorrelationService`** : pour chaque test en échec/erreur,
  cherche les logs ERROR/WARN les plus pertinents en deux temps :
  1. d'abord parmi les logs émis pendant la fenêtre d'exécution des tests
     (signal le plus fort) ;
  2. sinon (ou si aucun log n'est trouvé dans cette fenêtre), parmi
     l'ensemble des logs connus, classés par similarité textuelle
     (réutilise `TextSimilarityUtil` du Sprint 3) entre le nom du test /
     message d'échec et le message de log.
  La méthode utilisée est toujours indiquée explicitement dans
  `correlationNote`, pour rester transparent sur la fiabilité du résultat
  — un point important à expliquer en soutenance.
- **`GET /api/tests/failures/correlated`** : nouvel endpoint qui expose
  cette corrélation, prêt à être affiché dans le futur dashboard
  ("ce test a échoué, voici probablement pourquoi").

## Ce qui a été fait au Sprint 6 (et pourquoi)

- **Plugin `dependency-check-maven`** ajouté au `pom.xml`, configuré pour
  générer un rapport JSON dans `target/`. Non lié au cycle de build normal
  (`mvn test`/`mvn package`) : il se déclenche explicitement via
  `POST /api/security/scan/dependencies`, car le premier scan télécharge la
  base NVD et peut être long.
- **`Vulnerability`** : modèle normalisé (id, source, composant affecté,
  gravité, score CVSS, description, statut). Pensé pour accueillir dès le
  Sprint 7 les résultats de SonarQube (`source: "sonarqube"`) et Trivy
  (`source: "trivy"`) sans changer la structure ni l'API exposée.
- **`DependencyCheckReportParser`** : lit le rapport JSON avec Jackson (déjà
  présent via Spring Web, aucune dépendance ajoutée), en préférant le score
  CVSS v3 (plus récent) et en repliant sur v2 puis sur le champ générique
  `severity` si besoin — le format des rapports Dependency-Check varie
  légèrement selon les versions, d'où ce parsing défensif.
- **`VulnerabilityStatusStore`** : mémorise en mémoire le statut de
  traitement de chaque vulnérabilité (nouvelle / en cours / corrigée),
  car le rapport lui-même ne connaît que la détection, pas le suivi.
- **`SecurityController`** : `POST /api/security/scan/dependencies`,
  `GET /api/security/vulnerabilities` (triées par score CVSS décroissant),
  `PATCH /api/security/vulnerabilities/{id}/status`.

## Prochaine étape (Sprint 7)

- Intégrer SonarQube (nécessite un serveur — le plus simple est de le
  lancer via Docker : `docker run -d -p 9000:9000 sonarqube:community`)
  pour l'analyse statique du code.
- Intégrer Trivy pour scanner les images Docker construites.
- Consolider les trois sources (dépendances, code, images) dans une seule
  vue, toujours via le modèle `Vulnerability` déjà en place.

## Ce qui a été fait au Sprint 7 (et pourquoi)

- **`SonarIssueFetcher`** + **`SonarIssueMapper`** : le backend ne déclenche
  pas lui-même l'analyse Sonar (elle se fait via `mvn sonar:sonar` en ligne
  de commande ou, plus tard, dans le pipeline CI/CD) — il se contente de
  consulter l'API Web de Sonar (`/api/issues/search`) une fois l'analyse
  publiée, et de mapper ses sévérités (BLOCKER/CRITICAL/MAJOR/MINOR/INFO)
  vers notre échelle commune.
- **`TrivyScanService`** + **`TrivyReportMapper`** : Trivy est lancé comme
  processus externe (`trivy image --format json`), sa sortie JSON est lue
  directement depuis stdout (pas de fichier intermédiaire) et mappée vers
  `Vulnerability`. Trivy utilise déjà l'échelle LOW/MEDIUM/HIGH/CRITICAL,
  donc le mapping est direct.
- **Logique de mapping extraite** (`SonarIssueMapper`, `TrivyReportMapper`)
  dans des classes sans appel réseau/processus, spécifiquement pour rester
  testables sans avoir besoin d'un vrai serveur Sonar ni du binaire Trivy
  installés — un point à souligner en soutenance (tests unitaires rapides
  et fiables, indépendants de l'infrastructure externe).
- **`SecurityController.getVulnerabilities()`** consolide maintenant
  dépendances + Sonar dans une seule liste triée par score CVSS
  décroissant (Trivy peut être ajouté à la même consolidation si son
  dernier résultat est sauvegardé — amélioration possible si le temps
  le permet).

## Prochaine étape (Sprint 8)

- Démarrer la brique Intelligence Artificielle : microservice Python/FastAPI
  qui reçoit un incident (log, échec de test ou vulnérabilité) et appelle
  une API de modèle de langage pour en générer une explication en langage
  naturel — première étape vers la proposition de corrections.

## Ce qui a été fait aux Sprints 8-9 (et pourquoi)

- **`ia-service/`** : microservice Python/FastAPI totalement indépendant du
  backend Spring Boot, communiquant en HTTP/JSON. Ce découplage permet de
  faire évoluer la brique IA (changer de fournisseur, ajouter un second
  avis...) sans toucher au reste de l'assistant — d'ailleurs le fournisseur
  a été changé pour Groq après coup sans qu'une seule ligne du backend Java
  n'ait eu besoin d'être modifiée, la preuve concrète de l'intérêt de ce
  découplage.
- **`prompts.py`** : sépare la construction du prompt du reste du code —
  le prompt engineering est itératif, autant l'isoler clairement. Le modèle
  est instruit de répondre en JSON structuré (`explanation`, `suggested_fix`,
  `severity_assessment`), ce qui évite tout parsing fragile de texte libre
  côté backend.
- **`main.py`** : expose `/health` (vérifie la présence de la clé API) et
  `/explain`. Parsing défensif de la réponse du modèle (`_parse_json_response`)
  au cas où celui-ci ajoute du texte ou des balises markdown autour du JSON
  demandé — testé unitairement sans appel réseau réel.
- **`AiClient`** (backend Java) : appelle le microservice IA en HTTP, sur le
  même principe que `SonarIssueFetcher` (HttpClient du JDK, aucune
  dépendance supplémentaire).
- **`AiRecommendation` + `AiRecommendationStore` + `AiController`** :
  implémentent la règle centrale du cahier des charges — toute proposition
  de l'IA naît au statut `PENDING_REVIEW` et n'est **jamais appliquée
  automatiquement**. Un développeur doit explicitement l'approuver
  (`APPROVED`) ou la rejeter (`REJECTED`) via `PATCH .../status`. C'est un
  point à mettre en avant en soutenance : l'IA aide à la décision, elle ne
  décide pas à la place du développeur.
- **`docker-compose.yml`** mis à jour : le service `backend` dépend
  désormais de `ia-service`, avec la clé API injectée via variable
  d'environnement (jamais en dur dans les fichiers versionnés).

## Prochaine étape (Sprint 10)

- Démarrer le frontend Angular : structure du projet, authentification
  simple (développeur / responsable qualité), squelette de navigation
  entre les vues logs / tests / sécurité / IA.

## Sprint 10 — Frontend Angular : installation

Les fichiers sources Angular sont fournis dans `frontend-src/` (composants,
services, styles). Je ne les ai pas mis directement dans un dossier
`frontend/` complet, car la configuration générée par Angular CLI
(`angular.json`, `tsconfig.json`...) dépend précisément de la version de
CLI installée sur votre machine — l'écrire à la main serait fragile.
Marche à suivre :

```bash
# 1. Installer Angular CLI si ce n'est pas déjà fait
npm install -g @angular/cli

# 2. Se placer à la racine du projet et générer le squelette Angular
cd assistant-devsecops
ng new frontend --standalone --routing --style=scss --skip-git

# Répondez "No" à la question sur le SSR/SSG (pas nécessaire pour ce projet)

cd frontend
```

Puis copiez les fichiers de `frontend-src/` dans `frontend/src/` en respectant cette correspondance :

| Fichier fourni (`frontend-src/...`) | Destination (`frontend/src/...`) |
|---|---|
| `environments/environment.ts` | `environments/environment.ts` *(créer le dossier)* |
| `styles.scss` | `styles.scss` *(remplace le fichier vide généré)* |
| `app/app.routes.ts` | `app/app.routes.ts` *(remplace)* |
| `app/app.config.ts` | `app/app.config.ts` *(remplace)* |
| `app/app.component.ts` | `app/app.component.ts` *(remplace ; vous pouvez supprimer `app.component.html/.css/.spec.ts` générés, plus utilisés)* |
| `app/core/*` | `app/core/*` *(créer le dossier, copier les 4 fichiers)* |
| `app/features/*` | `app/features/*` *(créer le dossier, copier les 6 sous-dossiers)* |
| `public/branding/vermeg-logo.jpg` | `public/branding/vermeg-logo.jpg` *(si votre projet a un dossier `public/` à la racine ; sinon utilisez `assets/branding/vermeg-logo.jpg` → `src/assets/branding/vermeg-logo.jpg` et remettez le préfixe `assets/` dans les deux templates HTML qui référencent le logo)* |

Sous Windows (PowerShell), depuis la racine `assistant-devsecops` :

```powershell
Copy-Item frontend-src\environments frontend\src\environments -Recurse -Force
Copy-Item frontend-src\styles.scss frontend\src\styles.scss -Force
Copy-Item frontend-src\app\app.routes.ts frontend\src\app\app.routes.ts -Force
Copy-Item frontend-src\app\app.config.ts frontend\src\app\app.config.ts -Force
Copy-Item frontend-src\app\app.component.ts frontend\src\app\app.component.ts -Force
Copy-Item frontend-src\app\core frontend\src\app\core -Recurse -Force
Copy-Item frontend-src\app\features frontend\src\app\features -Recurse -Force
```

Puis lancez :

```bash
cd frontend
npm start
```

Ouvrez `http://localhost:4200` — **le backend doit tourner en parallèle**
(`mvn spring-boot:run` dans `backend/`) pour que les données s'affichent.
Connectez-vous avec n'importe quel nom d'utilisateur ; choisissez le rôle
"Responsable qualité" pour voir le contrôle supplémentaire (changement de
statut des vulnérabilités) réservé à ce rôle.

## Ce qui a été fait au Sprint 10 (et pourquoi)

- **Authentification simplifiée** (`AuthService`) : le backend n'a pas
  d'endpoint de connexion (ce n'était pas l'objet des sprints précédents,
  centrés sur les briques métier). Le frontend retient donc "qui est
  connecté" et son rôle côté client uniquement. C'est assumé et documenté
  comme limitation dans le code — un vrai backend d'authentification
  pourrait remplacer uniquement cette classe sans toucher au reste.
- **Rôle développeur vs responsable qualité** : dans la vue Sécurité, seul
  le rôle "Responsable qualité" peut changer le statut d'une vulnérabilité
  (nouvelle/en cours/corrigée) — un développeur consulte, le responsable
  qualité arbitre. C'est une illustration concrète de la distinction de
  rôles demandée dans le cahier des charges.
- **`ApiService`** centralise tous les appels HTTP vers le backend (une
  méthode par endpoint), pour ne pas disperser les URLs dans chaque
  composant et faciliter un futur ajout d'authentification par jeton.
- **Design "salle de contrôle"** : palette sombre, JetBrains Mono pour les
  données brutes (logs, IDs, messages techniques), Inter pour l'interface,
  panneaux à bordure de couleur plutôt que cartes arrondies avec ombre —
  choix pensés pour un outil de monitoring regardé en continu, pas pour
  une page vitrine.
- Les 4 vues (Logs, Tests, Sécurité, IA) sont **connectées aux vraies API**
  du backend depuis le premier jour, pas des maquettes statiques : tout ce
  que vous avez construit aux Sprints 1-9 est déjà visible et utilisable.

## Prochaine étape (Sprint 11)

- Polish UX : filtres (par sévérité, par service), recherche dans les logs,
  vérifier le responsive sur petit écran.
- Éventuellement : rafraîchissement automatique périodique des vues au lieu
  du bouton "Rafraîchir" manuel.

## Choix techniques (à date)

| Composant     | Choix                          | Justification courte |
|---------------|--------------------------------|------------------------|
| Backend       | Spring Boot (Java 17)          | Cohérent avec l'écosystème Vermeg |
| Frontend      | Angular (à venir)               | Idem |
| Service IA    | Python / FastAPI (à venir)      | Écosystème ML/NLP + simple à conteneuriser |
| Logs          | Fichier local pour l'instant, puis Loki/Grafana | Léger à mettre en place pour un PFE solo |
| Tests         | JUnit (backend), Cypress (à venir) | Standard, bonne intégration CI |
| Sécurité      | SonarQube, OWASP Dependency-Check, Trivy (à venir) | Outils standards du marché |
| CI/CD         | GitHub Actions (à confirmer)    | Simple à démarrer sans infra à gérer |
| Conteneurs    | Docker / Docker Compose         | Suffisant pour l'échelle d'un PFE |
