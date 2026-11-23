import os
import tempfile

_tmp = tempfile.mkdtemp()
os.environ["SB4D_DATABASE_URL"] = f"sqlite:///{_tmp}/test.db"
os.environ["SB4D_MEDIA_DIR"] = f"{_tmp}/media"
os.environ["SB4D_DEV_MODE"] = "true"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402
from app.seed import MOBILES  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:
        yield c


def login(client, who: str) -> dict:
    mobile = MOBILES[who]
    otp = client.post("/api/v1/auth/otp/request", json={"mobile": mobile}).json()["dev_otp"]
    tok = client.post("/api/v1/auth/otp/verify", json={"mobile": mobile, "otp": otp}).json()["token"]
    return {"Authorization": f"Bearer {tok}"}
