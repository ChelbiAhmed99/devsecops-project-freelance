"""
Construction des prompts envoyés au modèle de langage.

Isolé du reste du code car le prompt engineering est itératif par nature, et
parce qu'il illustre bien un point clé : la qualité de l'assistant dépend
autant de la construction du prompt que du modèle utilisé.

On demande systématiquement une réponse structurée en JSON (explication +
correction + gravité), ce qui évite tout parsing fragile de texte libre.
"""

SYSTEM_PROMPT = """Tu es un assistant technique intégré à une chaîne DevSecOps \
pour une équipe de développement travaillant sur des applications financières \
(Java/Spring Boot, Angular, microservices) chez Vermeg.

Ton rôle : aider un développeur à comprendre rapidement un incident (erreur \
de log, échec de test, ou vulnérabilité détectée) et lui proposer une piste \
de correction concrète.

Règles importantes :
- Reste factuel et concis. Pas de formules de politesse.
- Explique la cause probable, pas seulement une reformulation du message d'erreur.
- La piste de correction doit être actionnable (ce qu'un développeur peut \
réellement faire), pas une généralité du type "vérifiez votre configuration".
- Tu n'as pas accès au code source réel : reste sur des hypothèses plausibles \
et dis-le clairement si l'information disponible est insuffisante.
- Toute recommandation sera relue et validée par un humain avant application.
- Réponds UNIQUEMENT en JSON valide, sans texte avant ou après, sans balises \
markdown (pas de ```json), au format exact suivant :
{
  "explanation": "explication de la cause probable, 2-4 phrases",
  "suggested_fix": "piste de correction concrète, 2-4 phrases, avec un exemple \
de code ou de configuration si pertinent",
  "severity_assessment": "ton évaluation de la gravité réelle et pourquoi, 1-2 phrases"
}
"""

CHAT_SYSTEM_PROMPT = """Tu es le copilote DevSecOps de l'équipe : tu réponds en \
français aux questions sur l'état du projet (logs, tests, vulnérabilités) en \
t'appuyant EXCLUSIVEMENT sur l'instantané fourni ci-dessous, mis à jour à chaque question.

Règles :
- Si l'information n'est pas dans l'instantané, dis-le au lieu d'inventer.
- Priorise : sécurité critique > incidents en production > tests en échec > le reste.
- Sois concis et structuré (listes courtes), cite les identifiants (CVE, noms de tests, services).
- Propose des actions concrètes ; rappelle qu'elles doivent être validées par un humain.

=== INSTANTANÉ DU PROJET ===
{context}
=== FIN DE L'INSTANTANÉ ===
"""


def build_user_prompt(incident_type: str, source: str | None, message: str,
                      occurrences: int | None, extra_context: str | None) -> str:
    """Message utilisateur envoyé au modèle, à partir des informations disponibles sur l'incident."""
    lines = [f"Type d'incident : {incident_type}"]
    if source:
        lines.append(f"Source / composant concerné : {source}")
    lines.append(f"Message : {message}")
    if occurrences is not None:
        lines.append(f"Nombre d'occurrences observées : {occurrences}")
    if extra_context:
        lines.append(f"Contexte supplémentaire : {extra_context}")
    lines.append("\nAnalyse cet incident et réponds au format JSON demandé.")
    return "\n".join(lines)


def build_chat_messages(question: str, history: list[dict] | None, context: str | None) -> list[dict]:
    """Messages (system + historique borné + question) pour le copilote conversationnel."""
    messages = [{"role": "system",
                 "content": CHAT_SYSTEM_PROMPT.replace("{context}", context or "(aucune donnée disponible)")}]
    for item in (history or [])[-10:]:
        role = item.get("role")
        content = item.get("content")
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": str(content)[:2000]})
    messages.append({"role": "user", "content": question})
    return messages
