# Guide d'utilisation

Ce guide explique chaque écran, ce qui se passe quand on clique, et propose des scénarios de test à dérouler avec les
fichiers du dossier `samples/`. Chaque écran contient aussi un bloc d'aide dépliable (« Comment fonctionne cet écran ? »).

## Vue d'ensemble

La plateforme reçoit trois types d'informations sur un projet et les relie entre elles :

| Brique | Ce qui entre | Ce qui en sort |
|---|---|---|
| Logs | lignes de logs applicatifs | groupes d'anomalies, pics d'erreurs |
| Tests | rapports JUnit XML + couverture JaCoCo | taux de réussite, tests instables, échecs reliés aux logs |
| Sécurité | rapports OWASP Dependency-Check, Trivy, SonarQube | liste de vulnérabilités priorisée et suivie |
| IA | un incident de l'une des trois briques | cause probable + correction proposée, à valider par un humain |

Le **tableau de bord** résume le tout en un score de santé et une **quality gate** (feu vert ou rouge pour livrer).

Trois rôles : **Développeur** (consulte, ingère des logs, lance et importe des tests, utilise l'IA), **Responsable
qualité / sécurité** (en plus : scans de sécurité, statut des vulnérabilités, journal d'audit), **Administrateur**
(en plus : comptes utilisateurs, purge des logs).

---

## 1. Connexion

Saisir identifiant et mot de passe, ou cliquer sur l'un des comptes de démonstration pour pré-remplir. Après
5 erreurs de mot de passe, le compte est verrouillé 5 minutes. La session dure 8 heures et se ferme avec l'onglet.

## 2. Tableau de bord

- **Score de santé (0 à 100)** : 100 moins des pénalités — 15 par vulnérabilité critique ouverte et 4 par haute
  (40 max), 0,8 par % de tests en échec (25 max), 5 par anomalie de logs critique (20 max), 2 par pic d'erreurs (10 max).
- **Quatre cartes cliquables** (Logs, Tests, Sécurité, IA) : le chiffre principal de chaque brique.
- **Quality gate** : cinq conditions, toutes requises —
  0 vulnérabilité critique ouverte · 5 hautes au maximum · 95 % de tests réussis au minimum · 40 % de couverture
  de lignes au minimum · 10 anomalies de logs critiques au maximum.
- **Services** : état du backend, de la base, du service IA et de SonarQube. **Derniers traitements** : scans et
  exécutions de tests lancés depuis le dernier démarrage du serveur, avec leur résultat.
- **Anomalies prioritaires** et **Vulnérabilités à traiter en premier** : les cinq premières de chaque liste.
- **Exporter le rapport** : synthèse au format Markdown.

L'écran se rafraîchit seul à chaque événement. La pastille à côté de la cloche indique si les notifications temps
réel sont connectées (verte) ; la cloche liste les derniers événements.

## 3. Logs

Quatre onglets :

1. **Anomalies** — seules les lignes WARN, ERROR et FATAL sont analysées. Les parties variables des messages
   (nombres, identifiants, adresses IP) sont masquées, puis les messages identiques ou proches sont regroupés.
   Un groupe est **critique** à partir de 3 occurrences, ou dès la première s'il contient un FATAL. Chaque groupe
   reçoit une catégorie (base de données, réseau, sécurité, défaut de code…). Deux moteurs de regroupement :
   « Local » (mots communs) et « IA » (TF-IDF + DBSCAN, via le service IA).
2. **Pics de fréquence** — erreurs comptées par fenêtres de 5 minutes ; les fenêtres anormalement hautes sont
   surlignées.
3. **Explorateur** — recherche paginée par texte, niveau, service et période.
4. **Ingestion** — coller des logs ou importer un fichier (`.log`, `.txt`, `.json`).

« Expliquer (IA) » sur une anomalie ouvre une fenêtre avec la cause probable et la correction proposée.

## 4. Tests

### Ce que fait l'écran

Il ne crée pas de tests : il **collecte les résultats** des tests existants et les analyse. Chaque arrivée de résultats
crée une **exécution** numérotée dans l'historique.

### Les trois façons d'obtenir des résultats

| Méthode | Ce qui se passe | Prérequis |
|---|---|---|
| Bouton **Lancer les tests** | Le serveur exécute `mvn test` sur le projet analysé (par défaut : le backend de l'assistant lui-même), lit `target/surefire-reports/*.xml` et `target/site/jacoco/jacoco.xml`, enregistre l'exécution. | Maven et le projet présents sur le serveur (état affiché dans l'aide de l'écran) |
| **Importer des rapports** | Vous envoyez des fichiers JUnit XML produits ailleurs, plus `jacoco.xml` en option. | Aucun |
| **Pipeline CI/CD** | Le pipeline envoie ses rapports sur `POST /api/ci/tests` avec la clé d'API. | `CI_API_KEY` configurée |

### Déroulement d'un lancement

1. Clic sur **Lancer les tests** : le serveur répond tout de suite et travaille en arrière-plan.
2. Un bandeau affiche le temps écoulé. On peut changer d'écran ou recharger la page : le suivi reprend.
3. Maven compile puis exécute les tests (1 à 3 minutes ; plus au premier lancement).
4. À la fin, une notification apparaît et l'écran se recharge avec la nouvelle exécution.
5. Si Maven n'a pas pu démarrer ou si la compilation échoue, un encadré rouge affiche la fin de la sortie Maven ;
   les chiffres affichés restent ceux de l'exécution précédente.

### Lecture de l'écran

- **Quatre indicateurs** : tests exécutés, en échec (échecs d'assertion + erreurs d'exception), taux de réussite
  (réussis / exécutés hors ignorés), couverture de lignes et de branches.
- **Badges quality gate** : le taux de réussite et la couverture comparés à leurs seuils (95 % et 40 %).
- **Tendance** : courbes réussite et couverture ; les pastilles `#1 #2 …` ouvrent une exécution passée
  (rouge = au moins un échec).
- **Tests instables (flaky)** : tests qui ont à la fois réussi et échoué sur les 10 dernières exécutions.
  Instabilité = nombre de changements d'état / (exécutions − 1).
- **Suites de tests** : une ligne par classe de test ; la première suite en échec est ouverte d'office. Chaque test
  en échec montre son message, sa trace et un bouton « Expliquer (IA) ».
- **Échecs corrélés aux logs** : pour chaque test en échec, les 3 logs WARN/ERROR les plus ressemblants (mots communs
  avec le nom du test et le message d'échec). Pour une exécution lancée depuis l'interface, les logs émis pendant
  l'exécution sont prioritaires. La note sous chaque échec indique la méthode utilisée.

### Scénario de test A — import (aucun outil requis)

Compte : `dev`.

1. **Tests** → bas de page → *1. Rapports JUnit* : `samples/tests/TEST-1-avec-2-echecs.xml` ; *2. jacoco.xml* :
   `samples/tests/jacoco.xml` → **3. Importer**.
   Attendu : 8 tests, 2 en échec, 75 % de réussite, couverture 72,43 % (branches 57,27 %). Badge « Taux de réussite »
   en rouge, « Couverture » en vert. La suite `PaymentServiceTests` est dépliée.
2. **Logs** → Ingestion → importer `samples/logs/incident-paiement.log`. Revenir sur **Tests** : dans « Échecs corrélés
   aux logs », `shouldConnectToPaymentGateway` est relié aux erreurs « Connection timeout while reaching payment gateway ».
3. Cliquer **Expliquer (IA)** sur cet échec → lire la proposition → **Valider**. La fiche apparaît dans
   **Assistant IA → Recommandations → Validées**.
4. Importer `samples/tests/TEST-2-tout-reussi.xml`, de nouveau avec `jacoco.xml` (sans rapport de couverture, la
   condition « Couverture » de la quality gate échoue). Attendu : 100 % de réussite, la courbe remonte, et
   `shouldConnectToPaymentGateway` figure dans les tests instables (il alterne échec et succès).
5. Cliquer sur la pastille de l'exécution précédente : un bandeau indique que l'on consulte une ancienne exécution.
6. **Tableau de bord** : la ligne « Taux de réussite des tests » de la quality gate est repassée à OK.

### Scénario de test B — lancement réel

1. Dans l'aide de l'écran Tests, vérifier que « Maven » et « Projet analysé » sont en vert.
2. **Lancer les tests** → bandeau avec chronomètre → attendre la notification.
3. Attendu : une exécution « lancée depuis l'interface » contenant les suites du backend
   (`LogAnalysisTests`, `VulnerabilityServiceTests`, `ApiSecurityIntegrationTests`…) et la couverture réelle.

### Scénario de test C — pipeline CI (ligne de commande)

Avec `CI_API_KEY=ma-cle` dans `.env` :

```bash
curl -H "X-API-Key: ma-cle" \
     -F "reports=@samples/tests/TEST-1-avec-2-echecs.xml;type=application/xml" \
     -F "coverage=@samples/tests/jacoco.xml;type=application/xml" \
     "http://localhost:8080/api/ci/tests?branch=main&commit=abc1234"
curl -i -H "X-API-Key: ma-cle" http://localhost:8080/api/ci/quality-gate    # 200 = franchie, 412 = échec
```

L'exécution apparaît avec la mention « publiée par le pipeline CI/CD », la branche et le commit.

## 5. Sécurité

### Les trois sources

| Outil | Ce qu'il analyse | Durée | Prérequis du bouton |
|---|---|---|---|
| OWASP Dependency-Check | bibliothèques Java du `pom.xml` | 2 à 5 min (20 à 60 min la première fois sans `NVD_API_KEY`) | Maven + Internet |
| Trivy | une image Docker (paquets système + bibliothèques) | 1 à 3 min | Trivy + Internet ; accès au démon Docker pour une image locale |
| SonarQube | le code source (après `mvn sonar:sonar`) | quelques secondes | SonarQube démarré, `SONAR_ENABLED=true`, `SONAR_TOKEN` |

Sans ces outils, **importer un rapport JSON** donne le même résultat : choisir le type, puis le fichier.

### Ce qui se passe à chaque résultat

L'assistant compare le nouveau résultat avec ce qu'il connaît déjà **pour cet outil** :

- faille jamais vue → créée au statut **Nouvelle** (notification si elle est critique) ;
- faille déjà connue → gravité et date de dernière détection mises à jour ;
- faille ouverte absente du nouveau résultat → **Corrigée (auto)** ;
- faille corrigée qui réapparaît → rouverte, étiquette **régression**.

Le message sous chaque bouton résume ce bilan : « N détectées : X nouvelles, Y corrigées automatiquement, Z rouvertes ».

### Cycle de traitement d'une vulnérabilité

`Nouvelle` → **Prendre** → `En cours` (assignée à vous) → `Corrigée`
ou bien `Risque accepté` / `Faux positif`, avec une note justificative.
Seules « Nouvelle » et « En cours » comptent comme *ouvertes* dans les indicateurs et la quality gate.

### Lecture de l'écran

- **Compteurs cliquables** : critiques ouvertes, ouvertes, corrigées, délai moyen de correction (MTTR).
- **Colonne Prio. (0 à 100)** : CVSS × 8, +5 pour une dépendance ou une image, +8 si une version corrigée existe,
  +1 par semaine d'ancienneté (7 max). La liste est triée par ce score.
- **Clic sur une ligne** : panneau de détail avec description, version corrigée, encadré « Que faire ? », lien vers
  la fiche NVD, analyse IA, et le formulaire de suivi (statut, assignation, note).
- **Exporter CSV** : toute la liste, pour Excel.

### Scénario de test D — cycle de vie complet (aucun outil requis)

Compte : `qualite`. Les chiffres valent pour une base de démonstration neuve (13 vulnérabilités : 8 OWASP, 5 Trivy).

1. **Sécurité** : noter le nombre de critiques ouvertes. Cliquer sur le compteur : la liste se filtre.
2. Importer une « correction » : type *OWASP Dependency-Check*, fichier
   `samples/securite/dependency-check-2-apres-correction.json`.
   Attendu : « 6 détectées · 0 nouvelle · 2 corrigées · 0 rouverte ». Filtrer sur « Corrigée » : `CVE-2021-44228`
   (log4j) et `CVE-2022-42889` (commons-text) portent la mention « (auto) ».
3. Simuler une régression : importer `samples/securite/dependency-check-1-initial.json`.
   Attendu : « 8 détectées · 0 nouvelle · 0 corrigée · 2 rouvertes » ; les deux failles reviennent avec l'étiquette
   « régression ».
4. Sur une faille « Nouvelle », cliquer **Prendre** : statut « En cours », assignée à `qualite`.
5. Ouvrir une faille de sévérité moyenne, choisir « Risque accepté », écrire une note, **Enregistrer le suivi** :
   elle quitte la liste des ouvertes.
6. **Expliquer (IA)** sur `CVE-2021-44228` : l'IA décrit la faille et la montée de version à faire.
7. **Administration → Journal d'audit** : chaque action ci-dessus est tracée.
8. Se reconnecter avec `dev` : les boutons de scan et le formulaire de suivi ont disparu (lecture seule).

### Scénario de test E — scans réels

1. « Prérequis des scans » : vérifier les pastilles.
2. **Trivy** : saisir `nginx:1.27` (image publique) → **Scanner l'image**. Attendu après 1 à 3 minutes : de nouvelles
   lignes dont l'outil est Trivy.
3. **OWASP** : **Scanner les dépendances**. Prévoir une longue attente au premier lancement sans clé NVD.
4. **SonarQube** : `docker compose --profile sonar up -d`, créer un jeton dans SonarQube, lancer
   `mvn sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=…` dans `backend/`, renseigner
   `SONAR_ENABLED=true` et `SONAR_TOKEN` dans `.env`, redémarrer le backend, puis **Synchroniser SonarQube**.

## 6. Assistant IA

- **Copilote** : conversation libre. Chaque question est envoyée avec un instantané du projet (anomalies, tests,
  vulnérabilités ouvertes).
- **Recommandations** : toutes les analyses demandées via « Expliquer (IA) », filtrables par statut. Chaque fiche doit
  être **validée** ou **rejetée** par un humain, avec commentaire facultatif.

En haut à droite : `groq` si une clé de modèle de langage est configurée, sinon `heuristic` (règles locales).

## 7. Administration

- **Utilisateurs** (administrateur) : créer, modifier, désactiver, supprimer un compte ; mot de passe de 8 caractères
  minimum avec une lettre et un chiffre.
- **Journal d'audit** (administrateur et responsable qualité) : connexions, scans, imports, changements de statut.

---

## Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| « Backend injoignable » | backend arrêté ou en cours de démarrage | `docker compose ps` puis `docker compose logs backend` |
| Bouton « Lancer les tests » grisé | Maven ou `pom.xml` absents sur le serveur | importer des rapports, ou installer Maven |
| Scan OWASP très long ou en timeout | téléchargement de la base NVD sans clé | renseigner `NVD_API_KEY`, relancer |
| Trivy : « permission denied … docker.sock » | le backend n'a pas accès au démon Docker | `DOCKER_GID` (0 sous Docker Desktop), ou scanner une image de registre |
| SonarQube « désactivé ou injoignable » | profil non démarré ou jeton manquant | voir scénario E, étape 4 |
| Service IA « heuristic » au lieu de « groq » | clé absente ou invalide | renseigner `GROQ_API_KEY`, redémarrer `ia-service` |
| Heures décalées dans l'interface | fuseau du serveur | variable `TZ` dans `.env` |
| Connexion refusée avec les comptes initiaux | la base contient déjà d'autres comptes | Docker : `docker compose down -v` (efface les données) ; local : supprimer `backend/data/` |
