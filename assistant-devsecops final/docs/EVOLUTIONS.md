# Évolutions — validation multi-applications, diagnostic explicable et corrections assistées

Ce document décrit les évolutions apportées à l'Assistant DevSecOps **sans refonte** : l'architecture
(Spring Boot 3 + Angular standalone + service IA FastAPI), les écrans, les rôles et l'identité visuelle
Vermeg sont conservés. Aucune fonctionnalité existante n'a été supprimée ; les API existantes restent
compatibles (le paramètre `app` est partout optionnel).

---

## 1. Analyse de l'existant (avant évolution)

| Domaine | Déjà en place | Limites constatées |
|---|---|---|
| Tests | Import JUnit/Surefire, historique des exécutions, tests instables (flaky), couverture JaCoCo, quality gate CI | Un seul projet : toutes les exécutions mélangées, pas de rapport par application |
| Logs | Ingestion texte/flux, parsing Spring/Log4j/JSON, recherche, clustering d'anomalies (TF-IDF/DBSCAN + Jaccard), export Loki | Pas de format WildFly/JBoss, aucune distinction applicatif / serveur / CI, pas de corrélation causale |
| Sécurité | Import Trivy / Dependency-Check / Sonar, déduplication, statistiques | Contrainte d'unicité globale (`uk_vuln`) incompatible avec plusieurs applications |
| IA | Explication d'incidents (Groq + repli heuristique), recommandations, chat contextuel | Le LLM produit un texte libre ; aucune décision de validation, aucun suivi de la correction |
| Jira | **Absent** (vérifié : aucune classe, aucune configuration) | — |
| Rapports | Export de synthèse | Pas de rapport par application, pas de traçabilité de la décision humaine |
| Sécurité applicative | JWT, rôles DEVELOPER / QUALITY_MANAGER / ADMIN / CI, journal d'audit, SSE | Conservés tels quels et réutilisés pour les nouvelles actions sensibles |

---

## 2. Ce qui a été ajouté

### A. Validation multi-applications
- Nouvelle entité **`ManagedApplication`** (code, criticité LOW→CRITICAL, type de serveur, chemins de rapports,
  sources de logs, projet Jira, surcharges de politique en JSON).
- Colonne `application_code` sur les exécutions de tests, les logs et les vulnérabilités. Les données
  historiques sont rattachées à l'application par défaut (`assistant-devsecops`) au démarrage.
- **Campagne de validation** : l'utilisateur sélectionne N applications ; chacune est évaluée de façon
  **isolée** (une erreur sur l'une n'interrompt pas les autres) avec son score, son verdict, ses anomalies.
- **Portefeuille** : synthèse de toutes les applications (dernier verdict, score, tendance) sur le tableau de bord.
- Le pipeline CI peut publier pour une application précise (`app=` / `APP_CODE`), avec auto-enregistrement audité.

### B. Rapports automatiques par application
- Une campagne de 3 applications produit **3 rapports distincts** (JSON + Markdown + HTML imprimable),
  plus une archive `reports.zip`.
- Contenu : tests (taux, échecs, flaky, couverture), erreurs et incidents corrélés, extraits de logs,
  vulnérabilités, score détaillé par critère, risques, recommandations, avis IA, **décision humaine**
  (bloc délimité par des marqueurs, mis à jour sans régénérer le reste).

### C. Validation hybride (le LLM ne décide jamais seul)
Ordre d'évaluation (règles R0 → R5, toutes visibles dans le rapport sous forme de « cascade de décision ») :

1. **Mesures réelles** : 6 critères pondérés (succès des tests 30, sécurité 25, couverture 15,
   santé des logs 15, stabilité 10, régression 5).
2. **Règles bloquantes** (vulnérabilités critiques, taux de succès < seuil…) : rejet impossible à lever par l'IA.
3. **Score configurable** (`assistant.validation.*`, surchargeable par application).
4. **Avis du LLM** borné : ajustement de ± 5 points maximum, pondéré par le taux d'accord historique
   entre l'IA et les décisions humaines ; toute référence citée par le LLM qui n'existe pas dans les
   preuves (`allowed_refs`) est rejetée (garde anti-hallucination).
5. **Désaccord IA / règles** avec forte confiance, ou application CRITIQUE → **revue humaine imposée**.
6. **Décision humaine** (QUALITY_MANAGER / ADMIN) : confirmer ou passer outre, avec commentaire obligatoire, auditée.

Verdicts : `VALIDATED`, `VALIDATED_WITH_RESERVES`, `REJECTED`, `INCONCLUSIVE` (preuves insuffisantes) ;
indicateur séparé `reviewRequired` + motifs lorsque la revue humaine est imposée.

### D. Jira (nouveau)
- Recherche de tickets correspondant à une anomalie : empreinte stable (label `devsecops-<hash>`),
  mots-clés, similarité TF-IDF (service IA) ou Jaccard en repli.
- Brouillon pré-rempli (diagnostic + correction proposée) **modifiable** avant proposition.
- Workflow **proposer → approuver / rejeter** : rien n'est écrit dans Jira avant l'approbation d'un
  responsable qualité ; principe des **quatre yeux** (le proposeur ne peut pas approuver) ; rejet motivé ;
  exécution idempotente ; chaque étape dans le journal d'audit.
- Jira **Cloud** : `/rest/api/3/search/jql` (l'ancien `/rest/api/2/search` a été retiré par Atlassian) ;
  **Data Center** : `/rest/api/2/search` avec jeton personnel.
- Sans configuration : **mode simulation** — le workflow complet reste utilisable et tracé, sans appel externe.

### E. Logs JBoss / WildFly
- Parsing des formats `server.log` et console WildFly, et des logs CI (Jenkins / GitLab, y compris lignes sans horodatage).
- Classification **APPLICATION / SERVER / CICD**, sous-système serveur (`org.jboss.jca` → `wildfly-jca`…),
  extraction des codes d'erreur (`WFLYCTL…`, `IJ…`, `ARJUNA…`, `HHH…`, `ORA-…`, `SQLSTATE`…).
- **Corrélation d'incidents** : regroupement temporel (fenêtre de 5 min), 12 règles causales
  (base indisponible, saturation du pool, timeout de transaction, mémoire, disque, échec de déploiement,
  certificat TLS, service amont, configuration, clés d'authentification, compilation, tests en échec),
  chaîne cause → effets et tests impactés.

### F. Corrections assistées par IA
Cycle : **détection → analyse de l'origine → proposition expliquée → validation humaine →
déclaration d'application (référence commit/MR obligatoire) → vérification automatique**
(`VERIFIED`, `STILL_PRESENT`, `NOT_ENOUGH_DATA`). La vérification relit les nouveaux logs, tests et
scans ; **aucun code n'est modifié automatiquement**, en production ou ailleurs.

### G. Expérience utilisateur
Nouveaux écrans **Applications**, **Validation** (campagnes, portefeuille, historique) et **Rapport**
(jauge de score, cascade de décision, critères, risques). Composants réutilisables : jauge de score,
carte d'incident, assistant Jira, stepper de correction, sparkline. Libellés en français, couleurs et
tokens Vermeg existants.

---

## 3. Apport innovant (par rapport à un usage direct d'un LLM)

1. **Moteur de validation adaptatif** — la politique se durcit selon la criticité de l'application,
   compare l'application à **son propre historique** (détection de régression), et pondère le score
   par la **confiance dans les preuves** (tests anciens, couverture absente → confiance réduite → revue).
2. **Corrélation multi-couches explicable** — pour chaque incident, la cause racine est classée avec
   5 facteurs chiffrés et visibles : antériorité temporelle (0,30), règle causale (0,30),
   couche (0,15), exception racine (0,15), sévérité (0,10). On sait *pourquoi* la cause est proposée.
3. **IA contrôlée et apprenante** — le poids de l'avis IA dépend de son accord passé avec les humains ;
   ses citations sont vérifiées ; ses propositions de correction sont **vérifiées après coup** sur des données réelles.

Un LLM seul donne une opinion ; ici la décision est reproductible, justifiée, bornée et auditée.

---

## 4. Nouvelles API (toutes sous `/api`, JWT sauf mention)

| Méthode | Chemin | Rôle requis |
|---|---|---|
| GET | `/applications` · `/applications/{code}` · `/applications/{code}/quality-gate` | authentifié |
| POST / PUT | `/applications` · `/applications/{code}` | QUALITY_MANAGER, ADMIN |
| POST | `/validations/campaigns` (202, exécution asynchrone, progression SSE) | authentifié |
| GET | `/validations/campaigns` · `/{id}` · `/{id}/reports.zip` | authentifié |
| GET | `/validations/portfolio` · `/validations/history?app=` · `/validations/{id}` | authentifié |
| GET | `/validations/{id}/report.md` · `/validations/{id}/report.html?download=` | authentifié |
| POST | `/validations/{id}/decision` | QUALITY_MANAGER, ADMIN |
| GET | `/logs/incidents?app=` · `/logs/breakdown` | authentifié |
| GET / POST | `/jira/status` · `/jira/matches` · `/jira/draft` · `/jira/actions` | authentifié |
| POST | `/jira/actions/{id}/approve` · `/jira/actions/{id}/reject` | QUALITY_MANAGER, ADMIN |
| POST | `/ai/recommendations/{id}/applied` · `/ai/recommendations/{id}/verify` | authentifié |
| — | Endpoints CI existants : paramètres `app` et `logType` optionnels | clé `X-API-Key` |

Service IA : `POST /validate/assess`, `POST /ml/similar` (repli heuristique sans clé Groq).

---

## 5. Configuration

Sections ajoutées dans `backend/src/main/resources/application.yml` :
`assistant.applications`, `assistant.correlation`, `assistant.validation` (poids, seuils, règles
bloquantes, `llm.max-adjustment`, `regression`), `assistant.corrections`, `assistant.jira`.

Variables d'environnement (voir le bloc commenté ajouté à `.env` et `docker-compose.yml`) :
`DEFAULT_APPLICATION_CODE`, `CI_AUTO_REGISTER_APPLICATIONS`, `JIRA_ENABLED`, `JIRA_BASE_URL`,
`JIRA_DEPLOYMENT` (`cloud` | `datacenter`), `JIRA_EMAIL`, `JIRA_API_TOKEN`, `JIRA_DEFAULT_PROJECT`,
`JIRA_ISSUE_TYPE`, `JIRA_FOUR_EYES`. Le jeton Jira n'est jamais journalisé ni renvoyé au frontend.

Pipeline : `scripts/ci/publish-results.sh` accepte `APP_CODE`, `SERVER_LOG_FILE` et `CI_LOG_FILE`.

---

## 6. Migration et données

- `ddl-auto: update` crée les nouvelles tables/colonnes. **Au démarrage**, l'ancienne contrainte
  `uk_vuln` est supprimée (`DROP CONSTRAINT IF EXISTS`) et remplacée par `uk_vuln_app` (incluant l'application).
- Les données existantes sans application sont rattachées à `assistant-devsecops`.
- Mode démo (`ASSISTANT_DEMO_ENABLED=true`) : 4 applications d'exemple aux profils contrastés —
  `referentiel-clients` (LOW, validée), `back-office-titres` (HIGH, WildFly, réserves),
  `api-reporting` (MEDIUM, réserves), `portail-paiement` (CRITICAL, rejetée : vulnérabilités critiques).

---

## 7. Vérification

| Contrôle | Résultat |
|---|---|
| Compilation backend + tests unitaires (dont 33 nouveaux : parsing WildFly/CI, corrélation, moteur de validation, Jira) | 59 / 59 réussis |
| Tests du service IA (validation, similarité, heuristiques) | 23 réussis, 1 module ignoré (FastAPI absent hors-ligne) |
| Typage strict TypeScript du frontend | 0 erreur |
| Contrôles de templates Angular (identifiants, blocs `@if/@for/@let`) | 0 problème |

Ces contrôles ont été exécutés dans un environnement sans accès aux registres Maven/npm/PyPI
(bibliothèques remplacées par des bouchons). **À relancer dans votre environnement** avant fusion :

```bash
cd backend    && mvn clean verify
cd frontend   && npm ci && npm run build && npm test
cd ia-service && pip install -r requirements.txt && pytest
docker compose up --build
```
