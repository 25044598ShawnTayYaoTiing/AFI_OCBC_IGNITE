from fastapi.testclient import TestClient
from pydantic import ValidationError
import pytest

from app.main import app
from app.schemas import TransactionRecord


def test_health_does_not_need_credentials():
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


@pytest.mark.parametrize("count", ["1", True, -1, 1.5])
def test_processing_count_is_a_nonnegative_strict_integer(count):
    with pytest.raises(ValidationError):
        TransactionRecord(id="T-1", status="PROCESSED", idempotency_key="I-1",
                          processing_count=count, amount="0.10", currency="SGD",
                          source_reference="SRC-1", reconciliation_count=1,
                          reconciliation_matches=True)
