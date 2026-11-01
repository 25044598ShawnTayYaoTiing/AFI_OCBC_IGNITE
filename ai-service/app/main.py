"""Internal API. Spring owns authorization, persistence and every operational mutation."""

from hmac import compare_digest
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import Settings
from app.investigation.service import investigate
from app.schemas import InvestigationRequest, InvestigationResponse, SimulationRequest, SimulationResponse
from app.simulation.engine import simulate
from app.documents import ExtractRequest, Extraction, extract
from app.document_intelligence import InvestigationRequest as DocumentInvestigationRequest, InvestigationResult as DocumentInvestigationResult, investigate_documents, status as document_ai_status

app = FastAPI(
    title="AFI Resolve Investigation and Digital Twin",
    version="1.0.0",
    description="Synthetic records only. This stateless service cannot execute banking actions.",
)


def get_settings() -> Settings:
    return Settings.from_environment()


def require_service_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_service_key: Annotated[str | None, Header()] = None,
) -> None:
    if not settings.service_key:
        raise HTTPException(503, "Internal service authentication is not configured")
    if not x_service_key or not compare_digest(x_service_key.encode(), settings.service_key.encode()):
        raise HTTPException(401, "Invalid internal service credentials")


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail
    content = detail if isinstance(detail, dict) and set(detail) == {"message", "code"} else {"message": str(detail), "code": "SERVICE_ERROR"}
    return JSONResponse(status_code=exc.status_code, content=content)


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    # Do not echo untrusted request bodies, credentials or exception object internals.
    fields = [".".join(map(str, error["loc"])) for error in exc.errors()[:10]]
    return JSONResponse(status_code=422, content={"message": "Invalid request fields: " + ", ".join(fields), "code": "VALIDATION_ERROR"})


@app.get("/health", tags=["health"])
def health() -> dict[str, str]:
    return {"status": "UP", "service": "afi-resolve-ai", "mode": "synthetic-only"}


@app.post('/documents/extract', response_model=Extraction, dependencies=[Depends(require_service_key)], tags=['documents'])
def extract_document(request: ExtractRequest, settings: Annotated[Settings, Depends(get_settings)]) -> Extraction:
    return extract(request, settings)


@app.get('/documents/status', dependencies=[Depends(require_service_key)], tags=['documents'])
def document_status(settings: Annotated[Settings, Depends(get_settings)]):
    return document_ai_status(settings)


@app.post('/documents/investigate', response_model=DocumentInvestigationResult, dependencies=[Depends(require_service_key)], tags=['documents'])
def document_investigation(request: DocumentInvestigationRequest, settings: Annotated[Settings, Depends(get_settings)]):
    return investigate_documents(request, settings)


@app.post("/investigate", response_model=InvestigationResponse, dependencies=[Depends(require_service_key)], tags=["investigation"])
def investigate_case(
    request: InvestigationRequest, settings: Annotated[Settings, Depends(get_settings)],
) -> InvestigationResponse:
    return investigate(request, settings)


@app.post("/simulate", response_model=SimulationResponse, dependencies=[Depends(require_service_key)], tags=["simulation"])
def simulate_case(request: SimulationRequest) -> SimulationResponse:
    return simulate(request)
