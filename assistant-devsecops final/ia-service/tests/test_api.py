"""Rôle : tester les endpoints HTTP FastAPI et leur comportement en mode heuristique."""
import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


@pytest.fixture(autouse=True)
def no_llm(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)


client = TestClient(app)


def test_health_reports_heuristic_provider():
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["provider"] == "heuristic"
    assert body["api_key_configured"] is False


def test_explain_falls_back_to_heuristics():
    response = client.post("/explain", json={"incident_type": "log_anomaly", "source": "auth-service",
                                             "message": "JWT signature does not match", "occurrences": 5})
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "heuristic"
    assert body["explanation"] and body["suggested_fix"] and body["severity_assessment"]


def test_explain_validates_input():
    assert client.post("/explain", json={"incident_type": "log_anomaly", "message": ""}).status_code == 422


def test_cluster_endpoint():
    body = client.post("/ml/cluster", json={"messages": ["db timeout after #ms", "db timeout after #ms on node #",
                                                          "totally different"]}).json()
    assert len(body["labels"]) == 3
    assert body["labels"][0] == body["labels"][1]


def test_outliers_rejects_mismatched_sizes():
    assert client.post("/ml/outliers", json={"labels": ["a"], "features": []}).status_code == 400


def test_chat_endpoint():
    body = client.post("/chat", json={"question": "Des vulnérabilités ?",
                                      "context": "## Vulnérabilités ouvertes (1)\n- CVE-1 [HIGH]\n"}).json()
    assert body["provider"] == "heuristic"
    assert "CVE-1" in body["answer"]


def test_validate_assess_falls_back_to_heuristic_and_stays_grounded():
    payload = {
        "application": {"code": "titres", "name": "Back-office Titres", "criticality": "HIGH"},
        "deterministic": {"verdict": "VALIDATED_WITH_RESERVES", "score": 72.4, "confidence": 0.87, "blocking": []},
        "criteria": [{"code": "LOG_HEALTH", "label": "Santé des logs", "status": "WARN", "measured": "3 anomalies", "threshold": "≤ 3"}],
        "tests": {"new_failures": ["test:A#b"]},
        "incidents": [{"id": "incident-0123456789abcdef", "severity": "CRITICAL", "root_cause": "IJ000453", "confidence": 88}],
        "anomalies": [], "vulnerabilities": [],
        "allowed_refs": ["LOG_HEALTH", "test:A#b", "incident-0123456789abcdef"],
    }
    body = client.post("/validate/assess", json=payload).json()
    assert body["provider"] == "heuristic"
    assert body["opinion"] == "RESERVES"
    assert set(body["cited_refs"]) <= set(payload["allowed_refs"])


def test_similar_endpoint_scores_each_candidate():
    body = client.post("/ml/similar", json={"query": "Unable to acquire JDBC Connection",
                                             "candidates": ["JDBC connection pool exhausted", "Bouton mal aligné"]}).json()
    assert len(body["scores"]) == 2 and body["scores"][0] > body["scores"][1]
