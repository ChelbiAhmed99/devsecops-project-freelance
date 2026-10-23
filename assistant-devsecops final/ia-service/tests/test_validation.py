"""Rôle : tester l'avis de validation (repli déterministe), la similarité et la coercition des réponses LLM."""
import pytest

from app import heuristics
from app.ml import similarity


def _payload(verdict="VALIDATED", incidents=None, new_failures=None, vulns=None, confidence=0.9):
    refs = ["TEST_SUCCESS", "SECURITY", "incident-aaaaaaaaaaaaaaaa", "test:A#b",
            "vuln:trivy:CVE-2024-4741:libcrypto3"]
    return {
        "application": {"code": "titres", "name": "Back-office Titres", "criticality": "HIGH"},
        "deterministic": {"verdict": verdict, "score": 84.0, "confidence": confidence, "blocking": []},
        "criteria": [{"code": "TEST_SUCCESS", "label": "Réussite des tests", "status": "PASS", "measured": "99 %", "threshold": "≥ 97 %"},
                     {"code": "SECURITY", "label": "Sécurité", "status": "WARN", "measured": "2 hautes", "threshold": "hautes ≤ 1"}],
        "tests": {"new_failures": new_failures or []},
        "incidents": incidents or [],
        "vulnerabilities": vulns or [],
        "allowed_refs": refs,
    }


def test_heuristic_follows_rules_and_only_cites_allowed_refs():
    out = heuristics.assess_validation(_payload())
    assert out["opinion"] == "VALIDATE"
    assert out["adjustment"] == 0
    assert set(out["cited_refs"]) <= set(_payload()["allowed_refs"])
    assert "Sécurité" in out["justification"]


def test_heuristic_tempers_validation_when_a_critical_incident_is_open():
    incidents = [{"id": "incident-aaaaaaaaaaaaaaaa", "severity": "CRITICAL", "root_cause": "IJ000453: Unable to get managed connection",
                  "confidence": 88}]
    out = heuristics.assess_validation(_payload(incidents=incidents, new_failures=["test:A#b", "test:inconnu#x"]))
    assert out["opinion"] == "RESERVES"
    assert out["adjustment"] == -3.0
    assert "incident-aaaaaaaaaaaaaaaa" in out["cited_refs"]
    assert "test:A#b" in out["cited_refs"]
    assert "test:inconnu#x" not in out["cited_refs"]  # jamais de référence hors preuves


def test_heuristic_maps_rejection_and_low_confidence():
    out = heuristics.assess_validation(_payload(verdict="REJECTED", confidence=0.4))
    assert out["opinion"] == "REJECT"
    assert out["confidence"] <= 0.5
    vulns = [{"ref": "vuln:trivy:CVE-2024-4741:libcrypto3", "id": "CVE-2024-4741", "severity": "HIGH",
              "component": "libcrypto3", "fixed_version": "3.1.5-r0"}]
    out = heuristics.assess_validation(_payload(verdict="REJECTED", vulns=vulns))
    assert any("3.1.5-r0" in r for r in out["recommendations"])


def test_similarity_ranks_the_matching_ticket_first():
    scores = similarity("IJ000453: Unable to get managed connection for java:jboss/datasources/TitresDS",
                        ["Datasource TitresDS : Unable to get managed connection (IJ000453)",
                         "Export CSV vide sur le reporting quotidien",
                         ""])
    assert len(scores) == 3
    assert scores[0] > scores[1]
    assert all(0 <= s <= 1 for s in scores)
    assert similarity("x", []) == []


def test_coerce_assessment_bounds_llm_output():
    from app.validation import coerce_assessment
    out = coerce_assessment({"opinion": "validate", "confidence": 3, "adjustment": 40, "justification": "ok",
                             "risks": ["a"] * 20, "cited_refs": "pas une liste"}, "groq", "m")
    assert out.opinion == "VALIDATE" and out.confidence == 1 and out.adjustment == 5
    assert len(out.risks) == 8 and out.cited_refs == []
    with pytest.raises(ValueError):
        coerce_assessment({"opinion": "SHIP IT"}, "groq", "m")
