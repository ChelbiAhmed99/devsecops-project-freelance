"""
Modèles de machine learning (scikit-learn) utilisés par le backend :
Rôle : regrouper les messages d'erreur et détecter les fenêtres temporelles atypiques.

- cluster_messages : regroupement sémantique de messages d'erreur normalisés
  (TF-IDF sur mots + n-grammes de caractères, DBSCAN en distance cosinus).
  Plus robuste que la similarité de Jaccard aux variations de formulation.
- detect_outliers : détection de fenêtres temporelles atypiques
  (IsolationForest sur des vecteurs [erreurs, warnings, volume, messages
  distincts, services en erreur], standardisés).
"""
from __future__ import annotations

import numpy as np
from sklearn.cluster import DBSCAN
from sklearn.ensemble import IsolationForest
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.preprocessing import StandardScaler
from scipy.sparse import hstack

DEFAULT_EPS = 0.45


def cluster_messages(messages: list[str], eps: float = DEFAULT_EPS) -> list[int]:
    """Renvoie un numéro de cluster par message (-1 = message isolé)."""
    if not messages:
        return []
    if len(messages) == 1:
        return [0]
    texts = [m.lower().replace("#", " ") for m in messages]
    words = TfidfVectorizer(token_pattern=r"(?u)\b[a-zA-Z_][a-zA-Z0-9_.]+\b", sublinear_tf=True)
    chars = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True)
    blocks = []
    for vectorizer in (words, chars):
        try:
            blocks.append(vectorizer.fit_transform(texts))
        except ValueError:  # vocabulaire vide (messages composés uniquement de marqueurs/chiffres)
            continue
    if not blocks:
        # Aucun contenu exploitable : on regroupe les messages strictement identiques.
        index: dict[str, int] = {}
        return [index.setdefault(t.strip(), len(index)) for t in texts]
    matrix = hstack(blocks).tocsr()
    labels = DBSCAN(eps=eps, min_samples=2, metric="cosine").fit_predict(matrix)
    return [int(label) for label in labels]


def detect_outliers(labels: list[str], features: list[list[float]], contamination: float | str = "auto",
                    random_state: int = 42, min_score: float = 0.15) -> list[dict]:
    """
    Fenêtres atypiques selon IsolationForest, triées par score décroissant
    (plus haut = plus anormal). min_score écarte les écarts marginaux.
    """
    if len(labels) != len(features):
        raise ValueError("labels et features doivent avoir la même taille")
    if len(features) < 8:
        return []
    x = np.asarray(features, dtype=float)
    if np.allclose(x.std(axis=0), 0):
        return []
    x = StandardScaler().fit_transform(x)
    model = IsolationForest(n_estimators=200, contamination=contamination, random_state=random_state)
    predictions = model.fit_predict(x)
    scores = -model.decision_function(x)  # > 0 : plus anormal que le seuil
    result = [{"label": labels[i], "score": round(float(scores[i]), 4)}
              for i in range(len(labels)) if predictions[i] == -1 and scores[i] >= min_score]
    result.sort(key=lambda item: item["score"], reverse=True)
    return result
