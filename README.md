# Swasth Bal 4D – mobile app + backend

This is a working first version of the Swasth Bal 4D system from the design document (v0.1). It follows the design's own tech stack (section 13):

| Part | Stack | Folder |
|---|---|---|
| One Android app with role-based login (field worker · facility · department) | Kotlin + Jetpack Compose, CameraX, ML Kit, Room + SQLCipher, WorkManager | `android/` |
| Backend API | Python FastAPI, SQLAlchemy (PostgreSQL 16 in prod, SQLite for dev), JSON rules engine | `backend/` |
| Parent channel | WhatsApp Cloud API webhook + templates (logged to the DB when no WhatsApp keys are set) | `backend/app/routers/parent.py` |

## Run the backend

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
# API docs: http://localhost:8000/docs
pytest -q          # 20 end-to-end + rules tests
```

Or with PostgreSQL: `docker compose up` from the repository root.

In dev mode the server loads **synthetic** demo data that matches the screens in the design (no real children's data). It also returns the OTP in the response, so you don't need an SMS gateway:

| Role | Mobile | What opens |
|---|---|---|
| ASHA Sunita (Rampur, BKT block) | 9000000001 | Due list → guided 4D screening |
| Dr. Mehta, DEIC District Hospital | 9000000002 | Referral inbox → AI summary → accept / decline → QR check-in → outcome |
| ACMO-RBSK Lucknow | 9000000003 | Department dashboard |

## Run the Android app

1. Open the `android/` folder in Android Studio (Ladybug or newer). Let it sync Gradle. If Studio asks for a Gradle wrapper, accept it (wrapper version 8.11.1).
2. The app talks to the online backend at `https://swasthbal4d-api.onrender.com` by default. To use a backend on your laptop instead, build with `-PapiBaseUrl=http://10.0.2.2:8000/api/v1/` (emulator).
3. Run it on Android 10+. Log in with one of the numbers above, enter the OTP shown on screen, and set a 4-digit PIN.

## What maps to which screen in the design

| Design screen | App file |
|---|---|
| 1 Home: day list, priority badges, counts, sync state, map, QR | `ui/screens/worker/WorkerHomeScreen.kt` |
| 2–3 Child not present → reason → rebooked, parent told | `WorkerHomeScreen.kt` (`NotPresentSheet`) → `POST /due/{id}/absent` |
| 4 Identify: QR → RBSK / ID → search → wider mobile search → register | `ui/screens/worker/IdentifyScreens.kt` |
| 5–6 Consent item by item, OTP to the parent or recorded voice, refusal with a reason | `ui/screens/screening/ConsentScreens.kt` |
| Danger signs → emergency flow | `ConsentScreens.kt` (`DangerSignsScreen`) |
| 7 D1 guided camera: frame guide, live checks (light / steady / full face), auto-capture | `CaptureSteps.kt` + `media/CameraKit.kt` |
| 8 Questions read aloud + worker comment (type / voice) + "मुझे चिंता है" | `DomainScreens.kt` (`QuestionsStep`) |
| 9 D2 device reading: steps → photo → OCR → manual fallback | `CaptureSteps.kt` (`DeviceStep`) |
| 10 D4 tasks: name-call video, picture naming, star follow (front camera), eye chart, tone test | `D4Tasks.kt` |
| 11 Before submit | `DomainScreens.kt` (`BeforeSubmitScreen`) |
| 12 Result per D + final result (worst D decides), spoken in Hindi | `DomainScreens.kt` (`ResultScreen`) |
| 13–16 Parent WhatsApp: result, menu, uploads, diet, questions answered from approved content / prescription | `backend/app/routers/parent.py` |
| 17–20 Facility inbox, AI summary + evidence, accept / decline + re-route, QR check-in, outcome | `ui/screens/facility/FacilityScreens.kt` |
| 21 Department dashboard + questions in Hindi | `ui/screens/department/DashboardScreen.kt` |

## How the key rules from the design are built

- **Rules decide, AI cross-checks, the worse result wins.** `backend/app/rules/ruleset.json` holds the thresholds, questions and routing as versioned data. The same file ships in the app's offline pack, and `RulesEngine.kt` evaluates it on the phone for the provisional result. AI findings can only raise a result. If a finding cites no evidence ID, it is held at yellow.
- **Missing input is never green.** A skipped step or a missing required reading (for example MUAC for 6–59 months) makes that D INCOMPLETE and creates a retake task.
- **Hybrid online / offline.** On a stable connection, submit returns the FINAL result. Otherwise the app shows a PROVISIONAL result and saves the visit to an encrypted outbox (SQLCipher). WorkManager syncs it later. Sync is idempotent because the visit has a client-generated UUID. If the final result differs from the provisional one, the worker gets a notification and the parent gets a WhatsApp message.
- **Media.** Photos are saved as WebP (longest side 1280 px, quality 75) and audio as Opus 16 kHz mono. Uploads are queued (photo → audio → video), de-duplicated by SHA-256 and deleted from the phone after upload. The worker never sees media. A doctor sees it only for the referred Ds and only while the referral is open.
- **Result router.** Red creates one referral card per facility type (emergency → DH, SAM → NRC, D1 / D4 → DEIC …). Yellow creates a home plan, a 4-week re-check and a daily activity. Green sets the next date for the age band.
- **Referral loop.** The referral moves NOTIFIED → BOOKED → REACHED (QR or manual entry) → CLOSED, and closes only when the doctor records the outcome. A decline needs a reason and is logged as a training case. `POST /jobs/referral-ladder` runs the D-2 / D-1 / due-day reminders, the IVR call after a miss, the ASHA visit at D+3, and escalation at +7 / +14 days.
- Every access is written to an append-only audit log.

## Not finished yet / needs your decision

- **The Android app has not been compiled.** The environment I built it in has no Android SDK and no access to Google's Maven repository. I reviewed the code carefully, but expect to fix a few compile errors on the first Gradle sync in Android Studio. The backend was fully run and tested.
- **On-device AI models are simplified.** The phone uses ML Kit face detection, text recognition (OCR) and barcode scanning. It does not yet use MediaPipe pose, YAMNet, Vosk or Whisper. Hindi speech-to-text uses Android's speech recogniser.
- **Cloud AI is an adapter.** Set `SB4D_AI_BASE_URL` to an OpenAI-compatible endpoint (for example vLLM serving MedGemma in an India region). The two models then run in parallel on every photo. Without it, the result comes from rules, questions and the worker's concern only.
- **Thresholds.** Rules marked `"basis": "demo"` in `ruleset.json` are placeholders. The clinical committee must approve them before go-live.
- **Not built yet:** Keycloak / Jan Parichay single sign-on, row-level security in PostgreSQL, real government data adapters (JANANI, U-WIN, Poshan …), WhatsApp Flows, tus resumable uploads, and the separate React web portal. The facility and department screens are in the app now. They would be the same screens in a browser.
