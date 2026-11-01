"""Explicit wire schemas. Counts and booleans never accept string coercion."""

from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator

Action = Literal["RETRY_FULL_BATCH", "RESUME_PENDING_ONLY", "REQUEST_MANUAL_REVIEW"]
Identifier = Annotated[str, Field(strict=True, min_length=1, max_length=160)]
EvidenceLine = Annotated[str, Field(strict=True, min_length=1, max_length=2000)]
Count = Annotated[StrictInt, Field(ge=0)]


class Schema(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, validate_assignment=True)


class TransactionRecord(Schema):
    id: Identifier
    status: Literal["PROCESSED", "PENDING", "FAILED"]
    idempotency_key: Identifier
    processing_count: Count
    amount: Annotated[Decimal, Field(gt=0, max_digits=19, decimal_places=2, allow_inf_nan=False)]
    currency: Annotated[str, Field(strict=True, pattern=r"^[A-Z]{3}$")]
    source_reference: Identifier
    reconciliation_count: Count
    reconciliation_matches: StrictBool

    @field_validator("amount", mode="before")
    @classmethod
    def decimal_amount(cls, value: object) -> Decimal:
        # JSON numeric input is supported for Java BigDecimal interoperability.
        # Convert its text representation once; no floating point arithmetic is used.
        if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
            raise ValueError("amount must be a decimal number or decimal string")
        try:
            return Decimal(str(value))
        except InvalidOperation as exc:
            raise ValueError("amount must be a finite decimal") from exc


class InvestigationRequest(Schema):
    exception_id: Identifier
    exception_type: Literal["PARTIAL_BATCH_FAILURE", "DUPLICATE_TRANSACTION_RECORD", "RECONCILIATION_MISMATCH"]
    batch_id: Identifier
    source_hash: Annotated[str, Field(strict=True, min_length=1, max_length=128)]
    records: Annotated[list[TransactionRecord], Field(max_length=20_000)]
    failure_reason: Annotated[str, Field(strict=True, max_length=4000)] | None = None
    evidence: Annotated[list[EvidenceLine], Field(max_length=100)] = Field(default_factory=list)
    previous_incidents: Annotated[list[EvidenceLine], Field(max_length=50)] = Field(default_factory=list)


class ModelInvestigation(Schema):
    """The only output schema the language model can propose; never executable."""

    exception_id: Identifier
    investigation_status: Literal["COMPLETED"]
    probable_root_cause: Annotated[str, Field(strict=True, min_length=1, max_length=2000)]
    evidence: Annotated[list[EvidenceLine], Field(min_length=1, max_length=100)]
    candidate_actions: Annotated[list[Action], Field(min_length=1, max_length=3)]
    requires_additional_evidence: StrictBool
    explanation: Annotated[str, Field(strict=True, min_length=1, max_length=4000)]

    @field_validator("candidate_actions")
    @classmethod
    def unique_candidates(cls, values: list[Action]) -> list[Action]:
        if len(set(values)) != len(values):
            raise ValueError("candidate actions must be unique")
        if "REQUEST_MANUAL_REVIEW" in values and len(values) > 1:
            raise ValueError("manual review cannot be combined with automated candidates")
        return values


class InvestigationResponse(ModelInvestigation):
    method: Literal["DETERMINISTIC_FALLBACK", "GEMINI"]
    fallback_reason: Annotated[str, Field(strict=True, max_length=300)] | None = None


class SimulationRequest(InvestigationRequest):
    candidate_actions: Annotated[list[Action], Field(min_length=1, max_length=3)]

    @field_validator("candidate_actions")
    @classmethod
    def unique_candidates(cls, values: list[Action]) -> list[Action]:
        if len(set(values)) != len(values):
            raise ValueError("candidate actions must be unique")
        return values


class SimulationResult(Schema):
    simulation_id: Identifier
    exception_id: Identifier
    strategy: Action
    source_hash: str
    safe: StrictBool
    total_records: Count
    already_processed: Count
    pending_records: Count
    affected_records: Count
    expected_successful_records: Count
    duplicate_attempt_risk: Count
    policy_violations: list[str]
    decision: Literal["REJECTED", "ELIGIBLE", "REQUIRES_APPROVAL"]
    explanation: str
    created_at: datetime


class SimulationResponse(Schema):
    results: list[SimulationResult]
