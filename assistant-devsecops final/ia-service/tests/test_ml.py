"""Tests des modèles scikit-learn (clustering de messages et détection d'outliers)."""
import random

from app.ml import cluster_messages, detect_outliers


def test_similar_messages_share_a_cluster():
    labels = cluster_messages([
        "HikariPool-# - Connection is not available, request timed out after #ms",
        "HikariPool-# - Connection is not available, request timed out after #ms for tenant #",
        "JWT signature does not match locally computed signature for client #ip#",
        "JWT signature does not match locally computed signature",
        "Failed to generate regulatory report COREP-#",
    ])
    assert labels[0] == labels[1] and labels[0] >= 0
    assert labels[2] == labels[3] and labels[2] >= 0
    assert labels[0] != labels[2]
    assert labels[4] == -1  # message isolé


def test_cluster_handles_edge_cases():
    assert cluster_messages([]) == []
    assert cluster_messages(["seul"]) == [0]
    assert len(cluster_messages(["# #", "# # #"])) == 2


def test_isolation_forest_flags_error_burst():
    rng = random.Random(1)
    features = [[rng.randint(0, 2), rng.randint(0, 2), rng.randint(8, 12), 1, 1] for _ in range(40)]
    features[30] = [25, 4, 40, 3, 2]
    outliers = detect_outliers([f"b{i}" for i in range(40)], features)
    assert outliers and outliers[0]["label"] == "b30"


def test_outliers_need_enough_points_and_variance():
    assert detect_outliers(["a"] * 3, [[1.0, 2.0]] * 3) == []
    assert detect_outliers([f"b{i}" for i in range(10)], [[1.0, 1.0]] * 10) == []
