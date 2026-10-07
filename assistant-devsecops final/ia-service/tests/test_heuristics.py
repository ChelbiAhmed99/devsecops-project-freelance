"""Tests du moteur heuristique (repli sans LLM)."""
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
