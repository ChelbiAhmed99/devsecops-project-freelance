"""
Rôle : contrôler la réponse du LLM pour l'avis de validation (format, bornes) avant de la renvoyer au backend.
Le backend applique ensuite ses propres garde-fous (borne d'ajustement, ancrage des références).
"""
from app.schemas import ValidationAssessment


def coerce_assessment(parsed: dict, provider: str, model: str) -> ValidationAssessment:
    """Valide et borne la réponse du LLM ; lève ValueError si elle est inexploitable (=> repli heuristique)."""
    opinion = str(parsed.get("opinion", "")).strip().upper()
    if opinion not in ("VALIDATE", "RESERVES", "REJECT"):
        raise ValueError(f"Avis LLM invalide : {opinion!r}")

    def number(key: str, default: float, lo: float, hi: float) -> float:
        try:
            return max(lo, min(hi, float(parsed.get(key, default))))
        except (TypeError, ValueError):
            return default

    def strings(key: str) -> list[str]:
        values = parsed.get(key) or []
        return [str(v)[:400] for v in values if str(v).strip()][:8] if isinstance(values, list) else []

    return ValidationAssessment(opinion=opinion, confidence=number("confidence", 0.5, 0, 1),
                                adjustment=number("adjustment", 0, -10, 5),
                                justification=str(parsed.get("justification") or "")[:3000],
                                risks=strings("risks"), recommendations=strings("recommendations"),
                                cited_refs=strings("cited_refs"), provider=provider, model=model)
