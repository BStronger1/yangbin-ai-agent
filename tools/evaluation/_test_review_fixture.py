"""Synthetic contract fixture only; never writes labels or claims assessment results."""
from dataset_contract import DEFAULT_DATASET, review_packet


def synthetic_assisted_review():
    packet = review_packet(DEFAULT_DATASET, "synthetic-contract-fixture")
    packet.update(schemaVersion="travel-assistant-review-v1", reviewerKind="assistant",
                  status="ASSISTANT_DRAFT_REQUIRES_HUMAN_REVIEW", blind=False,
                  reviewedAt="2026-01-01T00:00:00+00:00")
    for row in packet["cases"]:
        for annotation in row["annotations"].values():
            annotation.update(label="NOT_ASSESSABLE",
                              reason="Synthetic contract fixture; no assessment performed",
                              evidencePointers=["/itinerary"])
    return packet
