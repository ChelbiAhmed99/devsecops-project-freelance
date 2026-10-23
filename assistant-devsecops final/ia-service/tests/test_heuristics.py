"""Rôle : vérifier les règles locales d'explication et les réponses du copilote sans LLM."""
from app import heuristics


def test_known_incident_is_recognized():
    result = heuristics.explain("log_anomaly", "payment-service",
                                "HikariPool-1 - Connection is not available, request timed out after 30000ms", 18, None)
    assert result["rule"] == "db_pool"
    assert "18 fois" in result["explanation"]
    assert result["suggested_fix"]


def test_log4shell_takes_precedence():
    result = heuristics.explain("vulnerability", "log4j-core-2.14.1.jar", "CVE-2021-44228 remote code execution", None, None)
    assert result["rule"] == "log4shell"
    assert "2.17.1" in result["suggested_fix"]


def test_generic_vulnerability_uses_fixed_version():
    result = heuristics.explain("vulnerability", "guava", "CVE-2023-2976 HIGH temp directory", None, "corrigée en 32.0.1")
    assert "32.0.1" in result["suggested_fix"]
    assert result["severity_assessment"].startswith("Élevée")


def test_chat_selects_relevant_section():
    context = ("## Anomalies de logs (1 groupes)\n- [CRITIQUE][DATABASE] pool saturé | 18 occurrences\n"
               "## Tests (dernière exécution)\n8 tests, 1 en échec\n"
               "## Vulnérabilités ouvertes (1)\n- CVE-2021-44228 [CRITICAL]\n")
    answer = heuristics.chat("Quelles vulnérabilités corriger ?", context)
    assert "CVE-2021-44228" in answer
    assert "pool saturé" not in answer
    assert "pool saturé" in heuristics.chat("Quelles sont les priorités ?", context)


def test_chat_without_context():
    assert "aucune donnée" in heuristics.chat("état ?", None)


def test_chat_greeting_does_not_dump_project_data():
    context = ("## Anomalies de logs (1 groupes)\n- [CRITIQUE][DATABASE] pool saturé | 18 occurrences\n"
               "## Vulnérabilités ouvertes (1)\n- CVE-2021-44228 [CRITICAL]\n")
    for greeting in ("bonjour", "Bonjour !", "salut", "merci", "qui es-tu ?"):
        answer = heuristics.chat(greeting, context)
        assert "CVE-2021-44228" not in answer
        assert "pool saturé" not in answer


def test_chat_unknown_question_asks_for_clarification():
    context = "## Vulnérabilités ouvertes (1)\n- CVE-2021-44228 [CRITICAL]\n"
    answer = heuristics.chat("quelle heure est-il sur Mars", context)
    assert "Je ne trouve rien" in answer
    assert "CVE-2021-44228" not in answer


_FULL = ("## Synthèse\n- Score de santé : 42/100 (Dégradé)\n- Quality gate (feu vert de livraison) : FAILED\n"
         "## Tests instables (flaky)\n- PaymentTest#refund : 2 échecs sur 5 exécutions\n"
         "## Tests (dernière exécution)\n8 tests, 1 en échec\n- PaymentTest#refund : expected 200\n"
         "## Vulnérabilités ouvertes (2)\n- CVE-2021-44228 [CRITICAL] log4j-core.jar\n- CVE-2023-2976 [HIGH] guava.jar\n"
         "## Recommandations IA\n- À valider : 3, validées : 1, rejetées : 0\n")


def test_chat_answers_any_project_topic():
    assert "42/100" in heuristics.chat("Quel est le score de santé ?", _FULL)
    assert "PaymentTest#refund" in heuristics.chat("Quels tests sont instables ?", _FULL)
    assert "À valider : 3" in heuristics.chat("Y a-t-il des recommandations à valider ?", _FULL)
    assert "guava" in heuristics.chat("guava", _FULL)            # recherche libre dans les données
    assert "feu vert" in heuristics.chat("C'est quoi la quality gate ?", _FULL)  # notion expliquée


def test_chat_follow_up_reuses_previous_topic():
    history = [{"role": "user", "content": "Pourquoi des tests échouent ?"}, {"role": "assistant", "content": "..."}]
    assert "PaymentTest#refund" in heuristics.chat("détaille", _FULL, history)
