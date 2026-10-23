"""
Microservice IA de l'assistant DevSecOps (FastAPI).
Rôle : exposer les API HTTP d'explication, de conversation et d'analyse ML.

- POST /explain      : explication + correction d'un incident (log, test, vulnérabilité)
- POST /chat         : copilote conversationnel alimenté par un instantané du projet
- POST /ml/cluster   : clustering TF-IDF + DBSCAN de messages d'erreur (scikit-learn)
- POST /ml/outliers  : détection de fenêtres atypiques (IsolationForest)
- POST /ml/similar   : similarité TF-IDF + cosinus (rapprochement anomalie / tickets Jira)
- POST /validate/assess : avis CONSULTATIF sur la validation d'une application (borné et contrôlé par le backend)
- GET  /health       : état et fournisseur actif

Fournisseurs : Groq (LLM, si GROQ_API_KEY est définie) avec repli automatique
sur un moteur heuristique local ("heuristic") si la clé est absente ou si
l'appel échoue. Le backend Java ne connaît que ce contrat HTTP/JSON.
"""
import logging
import os

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app import heuristics, llm, ml
from app.validation import coerce_assessment
from app.llm import _parse_json_response  # noqa: F401  (réexporté : utilisé par les tests historiques)
from app.prompts import (SYSTEM_PROMPT, VALIDATION_SYSTEM_PROMPT, build_chat_messages, build_user_prompt,
                         build_validation_prompt)
from app.schemas import (ChatRequest, ChatResponse, ClusterRequest, ClusterResponse, IncidentExplanation,
                         IncidentRequest, Outlier, OutlierRequest, OutlierResponse, SimilarityRequest,
                         SimilarityResponse, ValidationAssessment, ValidationAssessmentRequest)

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s [ia-service] %(name)s : %(message)s")
logger = logging.getLogger("assistant-ia-service")

app = FastAPI(
    title="Assistant DevSecOps - Service IA",
    description="Explique les incidents, propose des corrections et fournit les modèles ML de détection",
    version="1.0.0",
)

# En production, seul le backend appelle ce service (réseau Docker interne).
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in os.getenv("IA_CORS_ORIGINS", "http://localhost:4200").split(",") if o.strip()],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

HEURISTIC_MODEL = "rules-fr-v1"


@app.get("/health")
def health():
    configured = llm.is_configured()
    return {
        "status": "ok",
        "provider": "groq" if configured else "heuristic",
        "model": llm.DEFAULT_MODEL if configured else HEURISTIC_MODEL,
        "api_key_configured": configured,
        "ml": {"clustering": "tfidf-dbscan", "outliers": "isolation-forest", "similarity": "tfidf-cosine"},
    }


@app.post("/explain", response_model=IncidentExplanation)
def explain_incident(incident: IncidentRequest):
    if llm.is_configured():
        try:
            prompt = build_user_prompt(incident.incident_type, incident.source, incident.message,
                                       incident.occurrences, incident.extra_context)
            parsed = llm.complete_json(SYSTEM_PROMPT, prompt, regenerate=incident.regenerate)
            return IncidentExplanation(
                explanation=str(parsed.get("explanation") or "Explication indisponible."),
                suggested_fix=str(parsed.get("suggested_fix") or "Aucune piste de correction fournie."),
                severity_assessment=str(parsed.get("severity_assessment") or "Non évaluée."),
                provider="groq",
                model=llm.DEFAULT_MODEL,
            )
        except Exception:  # repli : le service reste utile même si le LLM échoue
            logger.exception("Appel LLM en échec, repli sur le moteur heuristique")

    result = heuristics.explain(incident.incident_type, incident.source, incident.message,
                                incident.occurrences, incident.extra_context)
    return IncidentExplanation(explanation=result["explanation"], suggested_fix=result["suggested_fix"],
                               severity_assessment=result["severity_assessment"],
                               provider="heuristic", model=HEURISTIC_MODEL)


@app.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest):
    if llm.is_configured():
        try:
            messages = build_chat_messages(request.question, [t.model_dump() for t in request.history],
                                           request.context)
            answer, model = llm.chat(messages)
            return ChatResponse(answer=answer, provider="groq", model=model)
        except Exception:
            logger.exception("Chat LLM en échec sur tous les modèles, repli sur le moteur heuristique")
    return ChatResponse(answer=heuristics.chat(request.question, request.context,
                                               [t.model_dump() for t in request.history]),
                        provider="heuristic", model=HEURISTIC_MODEL)


@app.post("/ml/cluster", response_model=ClusterResponse)
def cluster(request: ClusterRequest):
    labels = ml.cluster_messages(request.messages, eps=request.eps)
    return ClusterResponse(labels=labels, clusters=len({l for l in labels if l >= 0}))


@app.post("/ml/outliers", response_model=OutlierResponse)
def outliers(request: OutlierRequest):
    try:
        found = ml.detect_outliers(request.labels, request.features)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return OutlierResponse(outliers=[Outlier(**o) for o in found])


@app.post("/ml/similar", response_model=SimilarityResponse)
def similar(request: SimilarityRequest):
    return SimilarityResponse(scores=ml.similarity(request.query, request.candidates))


@app.post("/validate/assess", response_model=ValidationAssessment)
def assess_validation(request: ValidationAssessmentRequest):
    payload = request.model_dump()
    if llm.is_configured():
        try:
            parsed = llm.complete_json(VALIDATION_SYSTEM_PROMPT, build_validation_prompt(payload))
            return coerce_assessment(parsed, provider="groq", model=llm.DEFAULT_MODEL)
        except Exception:  # réponse illisible ou LLM indisponible : avis déterministe
            logger.exception("Avis de validation LLM inexploitable, repli sur le moteur heuristique")
    return ValidationAssessment(**heuristics.assess_validation(payload), provider="heuristic", model=HEURISTIC_MODEL)
