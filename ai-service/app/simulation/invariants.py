"""Shared evidence inspection; operational rules do not depend on a language model."""

from collections import Counter
from dataclasses import dataclass

from app.schemas import TransactionRecord


@dataclass(frozen=True)
class SnapshotFacts:
    total: int
    processed: int
    pending: int
    violations: tuple[str, ...]


def inspect_records(records: list[TransactionRecord]) -> SnapshotFacts:
    violations: list[str] = []
    if not records:
        violations.append("Snapshot contains no records; recovery evidence is insufficient")
    for attribute, label in (
        ("id", "transaction identifiers"),
        ("idempotency_key", "processing idempotency keys"),
        ("source_reference", "source references"),
    ):
        counts = Counter(getattr(record, attribute) for record in records)
        duplicates = sum(count - 1 for count in counts.values() if count > 1)
        if duplicates:
            violations.append(f"Snapshot contains {duplicates} duplicate {label}")

    wrong_processed_count = sum(r.status == "PROCESSED" and r.processing_count != 1 for r in records)
    wrong_pending_count = sum(r.status == "PENDING" and r.processing_count != 0 for r in records)
    wrong_processed_recon = sum(
        r.status == "PROCESSED" and (r.reconciliation_count != 1 or not r.reconciliation_matches)
        for r in records
    )
    wrong_pending_recon = sum(r.status == "PENDING" and r.reconciliation_count != 0 for r in records)
    unsupported_states = sum(r.status not in ("PENDING", "PROCESSED") for r in records)
    for count, rule in (
        (wrong_processed_count, "completed records do not have exactly one processing event"),
        (wrong_pending_count, "pending records already have processing events"),
        (wrong_processed_recon, "completed records do not have exactly one matching reconciliation entry"),
        (wrong_pending_recon, "pending records already have reconciliation entries"),
        (unsupported_states, "records have unsupported recovery states and need investigation"),
    ):
        if count:
            violations.append(f"{count} {rule}")
    return SnapshotFacts(
        total=len(records),
        processed=sum(r.status == "PROCESSED" for r in records),
        pending=sum(r.status == "PENDING" for r in records),
        violations=tuple(violations),
    )


def verified_evidence(facts: SnapshotFacts) -> list[str]:
    return [
        f"Batch contains {facts.total} records",
        f"{facts.processed} records are marked processed",
        f"{facts.pending} records remain pending",
        *facts.violations,
    ]
