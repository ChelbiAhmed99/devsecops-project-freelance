"""Contrats d'échange JSON (validés par Pydantic) entre le backend et le service IA."""
from typing import Literal, Optional

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
