"""
Moteur de secours à base de règles (fournisseur "heuristic").
Rôle : expliquer les incidents et répondre au copilote sans dépendre d'un LLM distant.

Utilisé quand aucune clé GROQ_API_KEY n'est configurée ou quand le LLM est
indisponible : l'assistant reste utile (démonstration hors ligne, environnement
sans accès Internet) avec des explications déterministes issues d'une base de
connaissances d'incidents courants en environnement Java/Spring.
"""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern
    explanation: str
    fix: str
    severity: str


def _r(name, regex, explanation, fix, severity):
    return Rule(name, re.compile(regex, re.IGNORECASE), explanation, fix, severity)


RULES: list[Rule] = [
    _r("log4shell", r"CVE-2021-44228|log4shell|log4j",
       "Log4Shell : Log4j 2 interprète les lookups JNDI présents dans les messages journalisés, ce qui permet "
       "à un attaquant d'exécuter du code à distance en injectant une chaîne ${jndi:ldap://...}.",
       "Mettre à jour log4j-core en 2.17.1 minimum (ou retirer la dépendance si Logback suffit). En attendant : "
       "-Dlog4j2.formatMsgNoLookups=true et blocage des sorties LDAP/RMI au niveau réseau.",
       "Critique : exécution de code à distance exploitée massivement, sans authentification."),
    _r("text4shell", r"CVE-2022-42889|text4shell|commons-text",
       "Text4Shell : Apache Commons Text évalue des interpolations (script:, dns:, url:) dans des chaînes "
       "potentiellement contrôlées par un utilisateur.",
       "Mettre à jour commons-text en 1.10.0 ou plus et éviter StringSubstitutor.createInterpolator() sur des entrées non fiables.",
       "Critique si des entrées utilisateur passent dans StringSubstitutor, sinon élevée."),
    _r("db_pool", r"hikari|connection is not available|connection pool|pool exhausted|could not open jpa entitymanager|cannotcreatetransaction",
       "Le pool de connexions à la base est saturé : toutes les connexions sont occupées (requêtes longues, "
       "transactions non fermées ou fuite de connexions) et les nouvelles demandes expirent après le délai d'attente.",
       "Identifier les requêtes lentes (pg_stat_activity), vérifier que les transactions sont courtes et fermées, "
       "activer spring.datasource.hikari.leak-detection-threshold=20000 puis ajuster maximum-pool-size "
       "en fonction de la charge et de max_connections côté PostgreSQL.",
       "Élevée : dégradation directe des transactions métier tant que la saturation persiste."),
    _r("db_timeout", r"(database|jdbc|sql).*(timeout|timed out)|(timeout|timed out).*(database|jdbc|sql)",
       "La base de données ne répond pas dans le délai imparti : indisponibilité, surcharge, verrous ou réseau.",
       "Vérifier la disponibilité de la base et la latence réseau, analyser les verrous et plans d'exécution, "
       "et configurer des timeouts cohérents (connection-timeout, query timeout) avec un retry borné côté appelant.",
       "Élevée si récurrente : les opérations dépendantes échouent."),
    _r("deadlock", r"deadlock",
       "Deux transactions se bloquent mutuellement en verrouillant les mêmes lignes dans un ordre différent.",
       "Accéder aux tables/lignes toujours dans le même ordre, raccourcir les transactions et rejouer "
       "automatiquement la transaction victime (retry idempotent).",
       "Moyenne à élevée selon la fréquence."),
    _r("oom", r"outofmemory|out of memory|java heap space|gc overhead",
       "La JVM a épuisé sa mémoire (heap) : fuite mémoire, chargement massif de données ou dimensionnement insuffisant.",
       "Capturer un heap dump (-XX:+HeapDumpOnOutOfMemoryError) et l'analyser (Eclipse MAT), paginer/streamer "
       "les gros volumes, puis ajuster -Xmx ou -XX:MaxRAMPercentage en conteneur.",
       "Critique : le service devient instable ou s'arrête."),
    _r("jwt", r"jwt|token signature|signature does not match|expired token|invalid token",
       "Des jetons d'authentification sont rejetés : clé de signature différente entre services, jeton expiré "
       "ou tentative d'utilisation de jetons forgés.",
       "Vérifier que tous les services partagent la même clé/JWKS et la même horloge (NTP), gérer le renouvellement "
       "des jetons côté client, et surveiller les IP sources si les échecs sont nombreux (possible attaque).",
       "Élevée si les échecs proviennent de sources inhabituelles (tentative d'intrusion), moyenne sinon."),
    _r("ssl", r"ssl|tls|certificate|pkix|handshake",
       "Échec de la négociation TLS : certificat expiré, autorité non reconnue ou protocole incompatible.",
       "Contrôler la date d'expiration et la chaîne du certificat (openssl s_client), importer l'autorité dans le "
       "truststore Java si nécessaire, et aligner les versions TLS.",
       "Élevée : les échanges avec le partenaire concerné sont interrompus."),
    _r("upstream", r"\b50[234]\b|service unavailable|bad gateway|gateway timeout|connection refused|unable to reach|unreachable",
       "Un service distant est indisponible ou surchargé (réponses 502/503/504 ou connexion refusée).",
       "Vérifier la santé du service cible et son autoscaling, ajouter un circuit breaker (Resilience4j) avec "
       "timeouts et retries exponentiels bornés, et une réponse dégradée côté appelant.",
       "Moyenne à élevée selon la criticité du service appelé."),
    _r("npe", r"nullpointer|null pointer|because .* is null",
       "Une valeur nulle est utilisée sans vérification : donnée absente en entrée ou cas métier non prévu.",
       "Identifier la variable nulle dans la trace, valider les entrées en amont (Objects.requireNonNull, "
       "@NotNull), utiliser Optional pour les valeurs facultatives et ajouter un test unitaire couvrant ce cas.",
       "Moyenne : erreur fonctionnelle localisée, mais bloquante pour le traitement concerné."),
    _r("assertion", r"assertion|expected .* but was|expected:<",
       "Un test automatisé a obtenu un résultat différent de l'attendu : régression fonctionnelle ou test non à jour.",
       "Reproduire le test localement, comparer avec le dernier commit où il passait (git bisect) et corriger "
       "le code ou le test selon la règle métier attendue.",
       "Moyenne : bloque la livraison tant que la cause n'est pas comprise."),
    _r("disk", r"no space left|disk full|quota exceeded",
       "L'espace disque est épuisé (logs, fichiers temporaires, volumes Docker).",
       "Purger et mettre en rotation les logs, nettoyer les images/volumes inutilisés (docker system prune) "
       "et ajouter une alerte sur le taux de remplissage.",
       "Élevée : risque d'arrêt des services et de perte de données."),
    _r("config", r"could not resolve placeholder|no qualifying bean|classnotfound|property .* not found|missing",
       "Un paramètre ou une ressource de configuration est absent pour l'environnement courant.",
       "Comparer les variables d'environnement et profils Spring entre environnements, et documenter la variable "
       "manquante dans le fichier .env.example / values du déploiement.",
       "Moyenne : généralement détectée au démarrage."),
]

SEVERITY_BY_LEVEL = {
    "CRITICAL": "Critique : à corriger immédiatement (score CVSS très élevé, exploitation possible à distance).",
    "HIGH": "Élevée : à planifier dans le sprint courant.",
    "MEDIUM": "Moyenne : à traiter dans un délai raisonnable.",
    "LOW": "Faible : à corriger lors d'une maintenance.",
}


def explain(incident_type: str, source: str | None, message: str,
            occurrences: int | None, extra_context: str | None) -> dict:
    text = " ".join(p for p in (message, extra_context or "") if p)
    for rule in RULES:
        if rule.pattern.search(text):
            explanation = rule.explanation
            if occurrences and occurrences > 1:
                explanation += f" L'incident a été observé {occurrences} fois"
                explanation += f" sur {source}." if source else "."
            return {"explanation": explanation, "suggested_fix": rule.fix,
                    "severity_assessment": rule.severity, "rule": rule.name}

    if incident_type == "vulnerability":
        level = next((lvl for lvl in SEVERITY_BY_LEVEL if lvl in text.upper()), None)
        fixed = re.search(r"(?:corrig[ée]e? en|fixed(?:version)?[:= ]+)\s*([\w.\-+]+)", text, re.IGNORECASE)
        fix = (f"Mettre à jour le composant vers la version {fixed.group(1)} ou supérieure, puis relancer le scan."
               if fixed else
               "Mettre à jour le composant vers une version corrigée (voir l'avis de sécurité du CVE), "
               "ou appliquer les mesures d'atténuation publiées, puis relancer le scan pour confirmer.")
        return {"explanation": f"Vulnérabilité connue détectée dans {source or 'un composant'} : {message[:300]}",
                "suggested_fix": fix,
                "severity_assessment": SEVERITY_BY_LEVEL.get(level, "À évaluer selon l'exposition du composant."),
                "rule": "generic-vulnerability"}

    if incident_type == "test_failure":
        return {"explanation": "Le test échoue sans motif reconnu automatiquement ; la trace et les logs "
                               "corrélés pendant l'exécution sont les meilleures pistes.",
                "suggested_fix": "Relancer le test isolément, vérifier les dépendances externes (base, services "
                                 "mockés) et comparer avec la dernière exécution réussie.",
                "severity_assessment": "Moyenne : bloque la validation de la livraison.",
                "rule": "generic-test"}

    return {"explanation": "Aucun motif connu n'a été reconnu dans ce message ; une analyse manuelle "
                           "(ou l'activation du LLM via GROQ_API_KEY) est recommandée.",
            "suggested_fix": "Examiner la trace complète, les logs corrélés dans la même fenêtre de temps "
                             "et les changements récents (déploiement, configuration).",
            "severity_assessment": "Indéterminée faute d'information suffisante.",
            "rule": "generic"}


def _sections(context: str | None) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = None
    for line in (context or "").splitlines():
        if line.startswith("## "):
            current = line[3:].strip()
            sections[current] = []
        elif current and line.strip():
            sections[current].append(line.strip())
    return sections


import re as _re
import unicodedata as _ud


def _norm(text: str) -> str:
    """minuscules, sans accents, ponctuation remplacée par des espaces."""
    t = _ud.normalize("NFD", (text or "").lower())
    t = "".join(c for c in t if _ud.category(c) != "Mn")
    return " " + _re.sub(r"[^a-z0-9#.]+", " ", t).strip() + " "


def _has(q: str, *words: str) -> bool:
    return any(w in q for w in words)


_GREETING = (" bonjour ", " bonsoir ", " salut ", " hello ", " hi ", " coucou ", " hey ", " slm ", " aslema ",
             " ahla ", " cc ")
_THANKS = (" merci ", " thanks ", " thank you ", " parfait ", " super ", " top ", " genial ", " ok ", " d accord ",
           " daccord ", " cool ", " yaatik ")
_BYE = (" au revoir ", " bye ", " a plus ", " bonne journee ", " bonne soiree ", " a bientot ")
_WHO = (" qui es tu ", " tu es qui ", " c est quoi ", " que peux tu ", " que sais tu ", " tu fais quoi ",
        " aide ", " help ", " comment ca marche ", " comment tu fonctionnes ")
_HOW_ARE_YOU = (" ca va ", " comment vas tu ", " comment allez vous ", " labes ")

# rubrique -> (préfixe du titre de section dans l'instantané, mots déclencheurs)
_TOPICS = {
    "synthese": ("Synthèse", (" sante", " score", " quality gate", " gate", " feu vert", " livr", " production",
                              " prod ", " pret", " release", " deployer")),
    "journaux": ("Journaux", (" combien de log", " lignes de log", " volume", " taux d erreur", " pic", " niveau",
                              " par service", " quel service", " quels services", " periode")),
    "anomalies": ("Anomalies", (" log", " anomal", " erreur", " exception", " incident", " panne", " backend",
                                " base de donnees", " database", " hikari", " timeout", " service")),
    "tests": ("Tests (dernière", (" test", " echec", " echou", " couverture", " junit", " surefire", " jacoco")),
    "historique_tests": ("Historique des tests", (" historique des tests", " evolution", " tendance",
                                                  " derniers runs", " dernieres executions")),
    "flaky": ("Tests instables", (" instable", " flaky", " aleatoire", " intermittent")),
    "stats_secu": ("Statistiques de sécurité", (" repartition", " gravite", " par outil", " delai",
                                                " combien de vuln", " combien de faille", " corrigee", " mttr")),
    "vulnerabilites": ("Vulnérabilités", (" vuln", " cve", " secu", " faille", " dependance", " image", " trivy",
                                          " owasp", " sonar", " composant", " librairie", " bibliotheque", " jar")),
    "recommandations": ("Recommandations", (" recommandation", " a valider", " validee", " rejete",
                                            " proposition", " suggestion")),
    "activite": ("Activité", (" activite", " audit", " qui a ", " derniere action", " historique des actions",
                              " connecte")),
    "traitements": ("Traitements", (" traitement", " job", " scan lance", " dernier scan", " import")),
}
_TOPIC_WORDS = {k: v[1] for k, v in _TOPICS.items()}

_GLOSSARY = {
    (" cve",): "Une CVE est l'identifiant public d'une faille de sécurité connue (ex. CVE-2021-44228). "
               "L'assistant les récupère via Trivy, OWASP Dependency-Check et SonarQube.",
    (" cvss",): "Le CVSS est une note de 0 à 10 qui mesure la gravité technique d'une faille. L'assistant "
                "la combine avec d'autres critères pour calculer un score de priorité de 0 à 100.",
    (" quality gate", " feu vert"): "La quality gate est le « feu vert » de livraison : elle échoue si un seuil "
                                    "n'est pas respecté (vulnérabilités critiques/hautes, réussite des tests, "
                                    "couverture, anomalies critiques).",
    (" devsecops",): "DevSecOps signifie intégrer la sécurité à chaque étape du développement et de la livraison, "
                     "plutôt qu'à la fin.",
    (" flaky", " instable"): "Un test instable (flaky) réussit puis échoue sans changement du code. L'assistant "
                             "les repère en comparant les exécutions successives.",
    (" couverture",): "La couverture indique la part du code exécutée par les tests (mesurée par JaCoCo).",
    (" score de sante", " sante"): "Le score de santé (sur 100) part de 100 et retire des points pour les "
                                   "vulnérabilités critiques et hautes, les tests en échec, les anomalies "
                                   "critiques et les pics d'erreurs.",
    (" dbscan", " tf idf", " regroup"): "Les erreurs de logs sont regroupées par ressemblance : TF-IDF transforme "
                                        "chaque message en vecteur, DBSCAN rassemble les messages proches.",
    (" isolation forest", " pic"): "Les pics sont détectés en comparant le nombre d'erreurs à l'habituel "
                                   "(z-score robuste) et avec le modèle Isolation Forest, qui isole les "
                                   "moments atypiques.",
    (" trivy",): "Trivy analyse les images Docker et leurs paquets système pour trouver des failles connues.",
    (" owasp", " dependency check"): "OWASP Dependency-Check analyse les bibliothèques Java du projet et signale "
                                     "celles qui ont des failles connues.",
    (" sonar",): "SonarQube analyse le code source (bugs, failles, mauvaises pratiques).",
    (" heuristique",): "Le mode heuristique est le plan B : des règles locales répondent quand le modèle de "
                       "langage en ligne (Groq) est indisponible.",
    (" llm", " modele de langage", " groq"): "Le copilote utilise un grand modèle de langage hébergé chez Groq, "
                                             "alimenté par un instantané à jour du projet.",
}
_DEFINITION_ASK = (" c est quoi", " qu est ce", " definition", " defini", " signifie", " veut dire",
                   " explique moi", " comment fonctionne", " comment marche", " comment est calcule", " a quoi sert")
_FOLLOW_UP = (" detail", " plus ", " encore", " la suite", " continue", " precise", " developpe", " et ")
_STOP = set("""avec dans pour quel quelle quels quelles sont est les des une un le la du de et ou que qui quoi
comment pourquoi combien elle il ils elles nous vous ont avoir etre faire fait cette ces mon ma mes ton ta tes
son sa ses leur leurs plus moins tout tous toutes projet assistant donne donner montre montrer liste lister
peux peut veux voudrais aujourd hui actuellement svp stp merci bonjour""".split())

_OVERVIEW = (" priorit", " resum", " etat ", " etat du", " global", " urgent", " quoi faire", " que dois je",
             " situation", " bilan", " synthese", " avant la mise en production",
             " mise en prod", " corriger en premier", " par ou commencer")

_EXAMPLES = ("- « Quelles sont les priorités aujourd'hui ? »\n"
             "- « Quelles vulnérabilités critiques corriger en premier ? »\n"
             "- « Pourquoi des tests échouent-ils ? »\n"
             "- « Quels services produisent le plus d'anomalies ? »")


def _items(lines: list[str]) -> list[str]:
    return [l for l in lines if l.startswith("- ")]


def chat(question: str, context: str | None, history: list[dict] | None = None) -> str:
    """Copilote de secours (sans LLM) : répond à la question posée, et seulement à elle.

    - salutations, remerciements, « qui es-tu ? » : réponse courte, sans données du projet ;
    - question ciblée (vulnérabilités, tests, logs) : uniquement la rubrique concernée ;
    - demande de priorités / d'état global : synthèse courte (santé, gate, sécurité, logs, tests) ;
    - notion (« c'est quoi une CVE ? ») : définition simple ;
    - relance (« détaille ») : reprend le sujet de la question précédente ;
    - autre question : recherche des mots de la question dans toutes les données du projet.
    """
    q = _norm(question)
    words = [w for w in q.split() if w]

    # 1. Échanges courants : pas de données projet.
    is_greeting = _has(q, *_GREETING)
    is_thanks = _has(q, *_THANKS)
    is_bye = _has(q, *_BYE)
    topics = [t for t, ws in _TOPIC_WORDS.items() if _has(q, *ws)]
    overview = _has(q, *_OVERVIEW)
    short = len(words) <= 4

    # Définition d'une notion : réponse pédagogique, éventuellement complétée par les données.
    if _has(q, *_DEFINITION_ASK) or (short and not topics and not overview):
        for keys, definition in _GLOSSARY.items():
            if _has(q, *keys):
                return definition

    # Question de relance (« détaille », « et les tests ? ») : on reprend le sujet précédent.
    if not topics and not overview and history and _has(q, *_FOLLOW_UP):
        for turn in reversed(history):
            if turn.get("role") == "user":
                prev = _norm(turn.get("content", ""))
                topics = [t for t, ws in _TOPIC_WORDS.items() if _has(prev, *ws)]
                if topics:
                    break

    if not topics and not overview:
        if is_bye:
            return "Au revoir ! Je reste disponible si vous avez besoin d'une analyse du projet."
        if _has(q, *_HOW_ARE_YOU):
            return "Tout va bien, merci ! Que souhaitez-vous savoir sur le projet ?"
        if is_greeting and (short or not _has(q, *_WHO)):
            return ("Bonjour ! Je suis le copilote DevSecOps. Posez-moi une question sur le projet, par exemple :\n"
                    + _EXAMPLES)
        if is_thanks and short:
            return "Avec plaisir ! Autre chose sur le projet ?"
        if _has(q, *_WHO):
            return ("Je suis le copilote DevSecOps : je réponds à vos questions à partir de l'état réel du projet "
                    "(anomalies de logs, résultats de tests, vulnérabilités ouvertes). Exemples :\n" + _EXAMPLES)

    sections = _sections(context)
    if not sections:
        return ("Je n'ai aucune donnée sur le projet pour le moment (importez des logs, des résultats de tests "
                "ou des rapports de scan), je ne peux donc pas encore répondre à cette question.")

    def find(prefix: str):
        for title, lines in sections.items():
            if _norm(title).strip().startswith(_norm(prefix).strip()):
                return title, lines
        return None, []

    # 2. Recherche d'un identifiant précis (CVE, nom de service, de test...) cité dans la question.
    ids = _re.findall(r"cve-\d{4}-\d+", (question or "").lower())
    if ids:
        hits = [l for lines in sections.values() for l in lines if any(i in l.lower() for i in ids)]
        if hits:
            return "Voici ce que je trouve :\n" + "\n".join(hits[:6])
        return f"{', '.join(i.upper() for i in ids)} n'apparaît pas parmi les éléments ouverts du projet."

    # 3. Aucun sujet reconnu : recherche libre des mots de la question dans les données du projet.
    if not topics and not overview:
        terms = [w for w in words if len(w) >= 4 and w not in _STOP]
        if terms:
            hits = []
            for title, lines in sections.items():
                for line in lines:
                    nl = _norm(line)
                    if any(" " + t in nl for t in terms) and line not in hits:
                        hits.append(line if line.startswith("-") else "- " + line)
            if hits:
                return (f"Voici ce que je trouve dans le projet pour « {question.strip()} » :\n"
                        + "\n".join(hits[:8]) + (f"\n(+ {len(hits) - 8} autre(s))" if len(hits) > 8 else ""))
        return ("Je ne trouve rien à ce sujet dans les données du projet. Je peux vous renseigner sur la santé du "
                "projet, la quality gate, les logs, les tests, la sécurité, les recommandations IA ou l'activité "
                "récente. Par exemple :\n" + _EXAMPLES)

    combien = _has(q, " combien ", " nombre ")

    # 4. Synthèse demandée : quelques lignes par rubrique.
    specific = [t for t in topics if t != "synthese"]
    if overview and (not specific or _has(q, " priorit", " resum", " global", " bilan")):
        out = ["Voici les priorités du moment :"]
        title, lines = find("Synthèse")
        out.extend(l for l in lines[:2])
        title, lines = find("Vulnérabilités")
        crit = [l for l in _items(lines) if "CRITICAL" in l]
        if title:
            out.append(f"- Sécurité : {title.split('(')[-1].rstrip(')')} vulnérabilité(s) ouverte(s)"
                       + (f", dont au moins {len(crit)} critique(s) — en tête : {crit[0][2:].split(' - source')[0]}"
                          if crit else ""))
        title, lines = find("Anomalies")
        crit = [l for l in _items(lines) if "[CRITIQUE]" in l]
        if title:
            out.append(f"- Logs : {title.split('(')[-1].rstrip(')')} d'anomalies"
                       + (f", la plus fréquente : {crit[0][2:].split(' | services')[0]}" if crit else ""))
        title, lines = find("Tests (dernière")
        if title and lines:
            out.append(f"- Tests : {lines[0]}")
        out.append("Ordre conseillé : vulnérabilités critiques, puis anomalies critiques, puis tests en échec. "
                   "Chaque action reste à valider par l'équipe.")
        return "\n".join(out)

    # 5. Rubrique(s) précise(s).
    out = []
    # rubriques les plus spécifiques d'abord ; on évite les doublons (ex. flaky + tests)
    if "flaky" in topics or "historique_tests" in topics:
        topics = [t for t in topics if t != "tests"]
    if "stats_secu" in topics and not _has(q, " cve", " lesquelles", " liste", " quelles"):
        topics = [t for t in topics if t != "vulnerabilites"]
    if "journaux" in topics and not _has(q, " anomal", " exception", " erreur"):
        topics = [t for t in topics if t != "anomalies"]
    for topic in topics:
        title, lines = find(_TOPICS[topic][0])
        if not title:
            out.append(f"{_TOPICS[topic][0]} : aucune donnée disponible.")
            continue
        items = _items(lines)
        header = [l for l in lines if not l.startswith("- ")]
        if topic in ("synthese", "journaux", "stats_secu", "historique_tests", "flaky", "recommandations",
                     "activite", "traitements"):
            out.append(f"{title} :")
            out.extend(lines[:10] if lines else ["- rien à signaler"])
            continue
        if topic == "vulnerabilites" and _has(q, " critique", " critical", " grave", " urgent", " premier"):
            items = [l for l in items if "CRITICAL" in l] or items
        if topic == "anomalies" and _has(q, " critique", " grave", " urgent"):
            items = [l for l in items if "[CRITIQUE]" in l] or items
        if topic == "tests" and not items and header:
            out.append(f"Tests : {header[0]}. Aucun test en échec à signaler.")
            continue
        if combien:
            out.append(f"{title}." + (f" {header[0]}." if header else ""))
            continue
        intro = f"{title} :" + (f" {header[0]}" if header else "")
        out.append(intro)
        out.extend(items[:5] if items else ["- rien à signaler"])
        if len(items) > 5:
            out.append(f"(+ {len(items) - 5} autre(s) — voir l'écran correspondant)")
    return "\n".join(out)
