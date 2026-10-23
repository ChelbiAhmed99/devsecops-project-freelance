"""Rôle : définir les contrats JSON validés par Pydantic entre le backend et le service IA."""
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


class IncidentRequest(BaseModel):
    incident_type: str = Field(..., description="log_anomaly | test_failure | vulnerability | other")
    source: Optional[str] = None
    message: str = Field(..., min_length=1, max_length=8000)
    occurrences: Optional[int] = Field(None, ge=0)
    extra_context: Optional[str] = Field(None, max_length=8000)
    regenerate: bool = Field(False, description="True : ignorer le cache et produire une nouvelle formulation")


class IncidentExplanation(BaseModel):
    explanation: str
    suggested_fix: str
    severity_assessment: str
    provider: str
    model: str


class ChatTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=4000)
    history: list[ChatTurn] = []
    context: Optional[str] = Field(None, max_length=20000)


class ChatResponse(BaseModel):
    answer: str
    provider: str
    model: str


class ClusterRequest(BaseModel):
    messages: list[str] = Field(..., max_length=5000)
    eps: float = Field(0.45, gt=0, lt=1)


class ClusterResponse(BaseModel):
    labels: list[int]
    clusters: int


class OutlierRequest(BaseModel):
    labels: list[str]
    features: list[list[float]]


class Outlier(BaseModel):
    label: str
    score: float


class OutlierResponse(BaseModel):
    outliers: list[Outlier]
    model: str = "IsolationForest"


# ---------- Validation hybride d'une application ----------
class ValidationAssessmentRequest(BaseModel):
    """Preuves structurées d'une application + décision déterministe calculée par le backend."""
    application: dict[str, Any] = Field(default_factory=dict)
    deterministic: dict[str, Any] = Field(default_factory=dict)
    criteria: list[dict[str, Any]] = Field(default_factory=list, max_length=20)
    tests: dict[str, Any] = Field(default_factory=dict)
    incidents: list[dict[str, Any]] = Field(default_factory=list, max_length=20)
    anomalies: list[dict[str, Any]] = Field(default_factory=list, max_length=30)
    vulnerabilities: list[dict[str, Any]] = Field(default_factory=list, max_length=30)
    allowed_refs: list[str] = Field(default_factory=list, max_length=500)


class ValidationAssessment(BaseModel):
    """Avis CONSULTATIF : le backend le borne, vérifie son ancrage et garde la décision finale."""
    opinion: Literal["VALIDATE", "RESERVES", "REJECT"]
    confidence: float = Field(..., ge=0, le=1)
    adjustment: float = Field(0, ge=-20, le=20)
    justification: str
    risks: list[str] = []
    recommendations: list[str] = []
    cited_refs: list[str] = []
    provider: str
    model: str


# ---------- Similarité (rapprochement anomalie <-> tickets Jira) ----------
class SimilarityRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=8000)
    candidates: list[str] = Field(..., max_length=200)


class SimilarityResponse(BaseModel):
    scores: list[float]
    method: str = "tfidf-cosine"
