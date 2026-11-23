from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import get_settings
from .db import Base, SessionLocal, engine
from .routers import auth, department, facility, parent, visits, worker
from .seed import seed


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)  # use Alembic migrations in production
    if get_settings().dev_mode:
        with SessionLocal() as db:
            seed(db)
    yield


app = FastAPI(title=get_settings().app_name, version="0.1.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

API = "/api/v1"
for r in (auth.router, worker.router, visits.router, visits.sync_router, facility.router, department.router, parent.router):
    app.include_router(r, prefix=API)


@app.get("/health")
def health():
    return {"status": "ok"}
