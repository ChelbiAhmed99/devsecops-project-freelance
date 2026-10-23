"""
Accès au modèle de langage (Groq, API compatible OpenAI) avec :
Rôle : centraliser les appels LLM, le parsing défensif et le cache des résultats.
- parsing JSON défensif des réponses ;
- cache LRU des explications (un même incident n'est pas re-facturé) ;
- import différé du SDK : le service démarre et répond en mode heuristique
  même si le SDK ou la clé ne sont pas disponibles.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import threading
from collections import OrderedDict

logger = logging.getLogger("assistant-ia-service.llm")

DEFAULT_MODEL = os.getenv("ASSISTANT_AI_MODEL", "openai/gpt-oss-120b")
# Modèles essayés dans l'ordre si le modèle principal échoue (quota Groq atteint, réponse vide...).
FALLBACK_MODELS = [m.strip() for m in os.getenv(
    "ASSISTANT_AI_FALLBACK_MODELS", "llama-3.3-70b-versatile,llama-3.1-8b-instant").split(",") if m.strip()]
CACHE_SIZE = int(os.getenv("ASSISTANT_AI_CACHE_SIZE", "256"))


def _parse_json_response(raw_text: str) -> dict:
    """
    Le modèle est instruit de répondre en JSON pur, mais on reste défensif :
    s'il ajoute du texte autour (ex: balises ```json ... ```), on extrait le
    bloc JSON ; à défaut, le texte brut devient l'explication.
    """
    text = (raw_text or "").strip()
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        try:
            parsed = json.loads(text[start:end + 1])
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    logger.warning("Réponse du modèle non-JSON, renvoi du texte brut en explication")
    return {"explanation": text, "suggested_fix": "", "severity_assessment": ""}


class LruCache:
    """Petit cache LRU thread-safe (pas de dépendance externe)."""

    def __init__(self, capacity: int):
        self.capacity = capacity
        self._data: OrderedDict[str, dict] = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key: str) -> dict | None:
        with self._lock:
            if key not in self._data:
                return None
            self._data.move_to_end(key)
            return self._data[key]

    def put(self, key: str, value: dict) -> None:
        with self._lock:
            self._data[key] = value
            self._data.move_to_end(key)
            while len(self._data) > self.capacity:
                self._data.popitem(last=False)

    def __len__(self) -> int:
        return len(self._data)


_cache = LruCache(CACHE_SIZE)

def _reasoning_kwargs(model: str) -> dict:
    """Les modèles « gpt-oss » acceptent reasoning_effort ; on le limite pour des réponses rapides."""
    return {"reasoning_effort": os.getenv("ASSISTANT_AI_REASONING", "low")} if "gpt-oss" in model else {}


_REASONING_KWARGS = _reasoning_kwargs(DEFAULT_MODEL)


def cache_key(*parts) -> str:
    return hashlib.sha256("\x1f".join("" if p is None else str(p) for p in parts).encode("utf-8")).hexdigest()


def api_key() -> str | None:
    key = os.getenv("GROQ_API_KEY")
    return key.strip() if key and key.strip() else None


def is_configured() -> bool:
    return api_key() is not None


def _client(max_retries: int = 2):
    from groq import Groq  # import différé
    return Groq(api_key=api_key(), timeout=45.0, max_retries=max_retries)


def chat_models() -> list[str]:
    return [DEFAULT_MODEL] + [m for m in FALLBACK_MODELS if m != DEFAULT_MODEL]


def complete_json(system_prompt: str, user_prompt: str, model: str = DEFAULT_MODEL,
                  regenerate: bool = False) -> dict:
    """Appelle le LLM et renvoie un dict (explanation, suggested_fix, severity_assessment).

    regenerate=True : le cache est ignoré et la température relevée, sinon « Régénérer »
    renverrait indéfiniment la même réponse mise en cache.
    """
    key = cache_key(model, system_prompt, user_prompt)
    if not regenerate:
        cached = _cache.get(key)
        if cached is not None:
            return cached
    response = _client().chat.completions.create(
        model=model,
        max_tokens=2500,
        temperature=0.8 if regenerate else 0.2,
        **_REASONING_KWARGS,
        messages=[{"role": "system", "content": system_prompt},
                  {"role": "user", "content": user_prompt}],
    )
    parsed = _parse_json_response(response.choices[0].message.content)
    _cache.put(key, parsed)
    return parsed


def chat(messages: list[dict], model: str | None = None) -> tuple[str, str]:
    """Copilote : essaie le modèle principal puis les modèles de secours.

    Renvoie (réponse, modèle réellement utilisé). Lève une exception seulement si
    tous les modèles ont échoué (main.py bascule alors sur le moteur heuristique).
    """
    models = [model] if model else chat_models()
    last_error: Exception | None = None
    for m in models:
        try:
            # max_tokens large : un modèle à raisonnement consomme une partie du budget en réflexion
            response = _client(max_retries=1).chat.completions.create(
                model=m, max_tokens=2500, temperature=0.5, messages=messages, **_reasoning_kwargs(m))
            answer = (response.choices[0].message.content or "").strip()
            if answer:
                return answer, m
            last_error = RuntimeError(f"Réponse vide du modèle {m}")
        except Exception as e:  # quota (429), indisponibilité, timeout...
            last_error = e
        logger.warning("Copilote : échec avec %s (%s), essai du modèle suivant", m, last_error)
    raise last_error or RuntimeError("Aucun modèle disponible")
