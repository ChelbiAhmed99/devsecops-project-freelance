"""
Tests unitaires du service IA (Sprint 8-9).
Rôle : vérifier la construction des prompts et le parsing des réponses du modèle.

Ne teste QUE la logique locale (construction de prompt, parsing défensif
du JSON) : aucun appel réel au LLM (Groq) n'est fait ici, pour que ces
tests restent rapides, gratuits, et exécutables sans clé API.

Lancer avec : pytest (depuis le dossier ia-service)
"""

from app.prompts import build_user_prompt
from app.llm import _parse_json_response


def test_build_user_prompt_includes_all_fields():
    prompt = build_user_prompt(
        incident_type="log_anomaly",
        source="payment-service",
        message="Connection timeout to database",
        occurrences=5,
        extra_context="Survenu après un déploiement",
    )

    assert "log_anomaly" in prompt
    assert "payment-service" in prompt
    assert "Connection timeout to database" in prompt
    assert "5" in prompt
    assert "après un déploiement" in prompt


def test_build_user_prompt_handles_missing_optional_fields():
    prompt = build_user_prompt(
        incident_type="vulnerability",
        source=None,
        message="CVE-2024-99999 detected",
        occurrences=None,
        extra_context=None,
    )

    assert "vulnerability" in prompt
    assert "CVE-2024-99999" in prompt
    # Ne doit pas planter ni insérer "None" littéralement dans le texte
    assert "None" not in prompt


def test_parse_json_response_with_pure_json():
    raw = '{"explanation": "test", "suggested_fix": "fix", "severity_assessment": "low"}'
    result = _parse_json_response(raw)

    assert result["explanation"] == "test"
    assert result["suggested_fix"] == "fix"


def test_parse_json_response_with_markdown_fences():
    raw = '```json\n{"explanation": "test", "suggested_fix": "fix", "severity_assessment": "low"}\n```'
    result = _parse_json_response(raw)

    assert result["explanation"] == "test"


def test_parse_json_response_falls_back_to_raw_text_when_not_json():
    raw = "Ceci n'est pas du JSON du tout."
    result = _parse_json_response(raw)

    assert result["explanation"] == raw
    assert result["suggested_fix"] == ""
