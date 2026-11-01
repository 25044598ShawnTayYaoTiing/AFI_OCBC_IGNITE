"""Configuration contains no database connection or execution credentials."""

from dataclasses import dataclass
import os
from pathlib import Path
from dotenv import load_dotenv

# Compose supplies process variables. Local launches also read ignored private files;
# existing process values always take precedence and no values are logged.
_service_root = Path(__file__).resolve().parents[1]
load_dotenv(_service_root / '.env', override=False, encoding='utf-8-sig')
load_dotenv(_service_root.parent / '.env', override=False, encoding='utf-8-sig')


@dataclass(frozen=True)
class Settings:
    service_key: str
    gemini_api_key: str
    gemini_model: str
    gemini_timeout_ms: int = 30_000

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            service_key=os.getenv("AI_SERVICE_KEY", "afi-internal-demo-key"),
            gemini_api_key=os.getenv("GEMINI_API_KEY", "").strip(),
            gemini_model=os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite").strip(),
            gemini_timeout_ms=max(1000, min(60_000, int(os.getenv("GEMINI_TIMEOUT_MS", "30000")))),
        )
