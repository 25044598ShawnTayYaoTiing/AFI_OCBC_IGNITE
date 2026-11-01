"""Configuration contains no database connection or execution credentials."""

from dataclasses import dataclass
import os


@dataclass(frozen=True)
class Settings:
    service_key: str
    gemini_api_key: str
    gemini_model: str
    gemini_timeout_ms: int = 15_000

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            service_key=os.getenv("AI_SERVICE_KEY", "afi-internal-demo-key"),
            gemini_api_key=os.getenv("GEMINI_API_KEY", "").strip(),
            gemini_model=os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip(),
            gemini_timeout_ms=max(1000, min(60_000, int(os.getenv("GEMINI_TIMEOUT_MS", "15000")))),
        )
