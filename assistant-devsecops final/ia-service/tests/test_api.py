"""Tests de l'API HTTP (FastAPI TestClient), en mode heuristique (sans clé LLM)."""
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
