"""
Moteur de secours à base de règles (fournisseur "heuristic").

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


def chat(question: str, context: str | None) -> str:
    """Réponse déterministe du copilote : sélectionne les parties pertinentes de l'instantané."""
    q = question.lower()
    sections = _sections(context)
    if not sections:
        return "Je n'ai aucune donnée sur le projet pour le moment (ingérez des logs, des résultats de tests ou des rapports de scan)."

    def find(prefix: str):
        for title, lines in sections.items():
            if title.lower().startswith(prefix):
                return title, lines
        return None, []

    wants = []
    if any(w in q for w in ("vuln", "cve", "sécu", "secu", "faille", "dépendance", "image")):
        wants.append("vulnérabilités")
    if any(w in q for w in ("test", "échec", "echec", "couverture", "flaky")):
        wants.append("tests")
    if any(w in q for w in ("log", "anomal", "erreur", "incident", "pic", "service")):
        wants.append("anomalies")
    if not wants or any(w in q for w in ("priorit", "résum", "resum", "état", "etat", "global", "urgent", "quoi faire")):
        wants = ["vulnérabilités", "anomalies", "tests"]

    parts = ["(Mode heuristique : réponse construite à partir de l'instantané du projet, sans LLM.)"]
    for want in wants:
        title, lines = find(want)
        if title:
            parts.append(f"**{title}**")
            parts.extend(lines[:6] if lines else ["- rien à signaler"])
    if len(wants) > 1:
        parts.append("Priorité suggérée : corriger d'abord les vulnérabilités CRITIQUES, puis les anomalies "
                     "critiques en production, puis les tests en échec. Chaque action reste à valider par l'équipe.")
    return "\n".join(parts)
