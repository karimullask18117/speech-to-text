# Speech to Text — real-time multilingual transcription

Speak Hindi, English, or a mix of both; words appear live as you talk and the finished
transcript is saved to a database. A dashboard lets you search, create, edit, and delete the history.

**Stack:** Java 17 · Spring Boot 3 (Web, WebSocket, Data JPA) · PostgreSQL (MySQL also supported; H2 for zero-setup) · vanilla JS + AudioWorklet · Gemini Live API.

## How it works

```
Browser mic ─► AudioWorklet (16 kHz PCM16) ─► WebSocket /ws/transcribe ─► Spring Boot
                                                                            │  (API key stays server-side)
live text ◄────────────── transcript fragments ◄──────── Gemini Live API ◄──┘
                                                          (inputAudioTranscription)
On "Stop": backend flushes the stream, saves the full transcript to PostgreSQL, pushes a "saved" event.
```

Gemini auto-detects language per utterance, so code-switching ("kal meeting hai, please confirm karna") works with no language setting.

## REST API (`/api/transcriptions`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/transcriptions?q=&page=&size=` | paginated, newest first, search title/text |
| GET | `/api/transcriptions/{id}` | |
| POST | `/api/transcriptions` | `{title?, text, language?, durationSeconds?}` |
| PUT | `/api/transcriptions/{id}` | |
| DELETE | `/api/transcriptions/{id}` | |

## Run locally

Get a key at https://aistudio.google.com/apikey.

```bash
# Option A: Docker (app + Postgres)
export GEMINI_API_KEY=your_key
docker compose up --build            # http://localhost:8080

# Option B: no database install (in-memory H2)
GEMINI_API_KEY=your_key SPRING_PROFILES_ACTIVE=h2 mvn spring-boot:run

# Option C: your own Postgres/MySQL
DB_HOST=... DB_USER=... DB_PASSWORD=... GEMINI_API_KEY=... mvn spring-boot:run
# MySQL: SPRING_DATASOURCE_URL=jdbc:mysql://host:3306/speech
```

Tests: `mvn test` (CRUD API, runs on H2).

## Configuration

| Env var | Default | |
|---|---|---|
| `GEMINI_API_KEY` | — | required for live transcription |
| `GEMINI_MODEL` | `gemini-3.1-flash-live-preview` | set to whichever Live model id your key can use (see https://ai.google.dev/gemini-api/docs/live-api) |
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | localhost/5432/speech/speech/speech | |
| `SPRING_DATASOURCE_URL` | built from the above | full JDBC override |

The microphone needs HTTPS (or localhost) — any cloud host below provides that.

## Deploy (Render, free tier)

1. Push this repo to GitHub.
2. Render → **New → Blueprint** → select the repo (uses `render.yaml`: Docker web service + free Postgres).
3. Set `GEMINI_API_KEY` in the dashboard. Open the generated `https://….onrender.com` URL.

The Dockerfile also works as-is on Railway, Fly.io, Google Cloud Run (set min-instances ≥1 if you want WebSockets warm), or any VM.

## Project layout

```
src/main/java/com/example/stt
  model/    Transcription entity
  repo/     Spring Data repository
  service/  CRUD + search/pagination
  web/      REST controller + DTO
  ws/       Browser WebSocket handler + Gemini Live bridge
src/main/resources/static   dashboard UI (index.html, pcm-worklet.js)
```

## Swapping in a fully open-source engine

`GeminiBridge` is the only class that knows about Gemini. To self-host instead, replace it with a bridge to a
streaming Whisper server (e.g. whisper_streaming / faster-whisper-server, or Vosk with its Hindi model) that accepts
16 kHz PCM and returns text fragments — the browser, WebSocket handler, and database layers don't change.
