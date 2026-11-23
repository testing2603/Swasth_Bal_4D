from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All settings come from environment variables (or a .env file)."""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="SB4D_", extra="ignore")

    app_name: str = "Swasth Bal 4D API"
    # SQLite for local dev/tests; use postgresql+psycopg2://user:pass@host/db in production
    database_url: str = "sqlite:///./swasthbal4d.db"
    jwt_secret: str = "change-me-in-production"
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = 60 * 24 * 30  # workers re-verify OTP every 30 days (design 3.2)
    otp_ttl_seconds: int = 300
    dev_mode: bool = True  # returns OTP in the response and seeds demo data
    media_dir: str = "./media"
    max_upload_mb: int = 25

    # Optional cloud AI (OpenAI-compatible endpoint, e.g. vLLM serving MedGemma in an India region)
    ai_base_url: str | None = None
    ai_api_key: str | None = None
    ai_model_primary: str = "google/medgemma-27b-it"
    ai_model_secondary: str = "Qwen/Qwen3-VL-235B-A22B-Instruct"

    # Optional WhatsApp Cloud API; without these, messages are logged only
    wa_phone_number_id: str | None = None
    wa_token: str | None = None
    wa_verify_token: str = "swasthbal4d-verify"


@lru_cache
def get_settings() -> Settings:
    return Settings()
