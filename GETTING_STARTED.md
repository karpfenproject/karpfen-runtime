# Getting Started Guide - Karpfen Runtime

Karpfen Runtime is a lightweight Kotlin/Ktor server with an HTTP API and a WebSocket endpoint.
It is runtime-only (no database, no persistence layer).

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Build and Run](#build-and-run)
4. [Configuration](#configuration)
5. [HTTP API](#http-api)
6. [WebSocket](#websocket)
7. [Testing](#testing)
8. [Architecture](#architecture)
9. [Troubleshooting](#troubleshooting)

## Prerequisites

- Java 21+
- Git

Gradle is included via wrapper (`gradlew` / `gradlew.bat`).

## Project Structure

```text
karpfen-runtime/
├── src/
│   ├── main/kotlin/
│   │   ├── Main.kt
│   │   └── io/karpfen/
│   │       ├── config/
│   │       │   └── ApplicationConfig.kt
│   │       ├── server/
│   │       │   ├── KtorServer.kt
│   │       │   ├── HTTPRoutes.kt
│   │       │   └── APIService.kt
│   │       └── websocket/
│   │           ├── WebSocketManager.kt
│   │           └── WebSocketMessage.kt
│   └── test/kotlin/
├── application.conf
├── HTTP_ENDPOINTS.md
└── GETTING_STARTED.md
```

## Build and Run

### Build

```powershell
./gradlew build
```

### Run

```powershell
./gradlew run
```

Default URL (from `application.conf`): `http://127.0.0.1:8080`

### Stop

Press `Ctrl+C`.

## Configuration

Runtime config is loaded from root `application.conf`.

Example:

```conf
server {
  host = "127.0.0.1"
  port = 8080
}

websocket {
  queueTimeoutMs = 1000
  enabled = true
}

logging {
  level = "INFO"
  consoleOutput = true
}
```

If the file is missing/invalid, defaults are used.

## HTTP API

HTTP routing is implemented in `src/main/kotlin/io/karpfen/server/HTTPRoutes.kt`.

For full endpoint reference with examples and error mapping, see `HTTP_ENDPOINTS.md`.

### Quick endpoint list

- `POST /createEnvironment`
- `PUT /setMetamodel?envKey=...` (body: metamodel text)
- `PUT /setModel?envKey=...` (body: model text)
- `PUT /setStateMachine?envKey=...&attachedTo=...` (body: state machine text)
- `POST /setTickDelay?envKey=...&milliseconds=...`
- `POST /registerObjectObserver?envKey=...&clientId=...&objectId=...`
- `POST /registerDomainListener?envKey=...&clientId=...&domain=...`
- `GET /health`

### Error behavior

- Success: `200 OK`
- Exceptions are returned as:
  - `400 Bad Request` for `IllegalArgumentException`
  - `409 Conflict` for `IllegalStateException`
  - `500 Internal Server Error` for all other exceptions
- Response body contains the exception message

## WebSocket

### Endpoint

- `ws://127.0.0.1:8080/ws`

### Message format

```json
{
  "environmentKey": "string",
  "messageType": "string",
  "payload": "string"
}
```

Incoming messages are queued in `WebSocketManager` (`LinkedBlockingQueue`) and can be consumed by the main thread.

## Testing

### Run all tests

```powershell
./gradlew test
```

### Run a specific test class

```powershell
./gradlew test --tests KtorServerIntegrationTest
```

Test report:

```text
build/reports/tests/test/index.html
```

## Architecture

```text
Main.kt
├── loads ApplicationConfig from application.conf
├── starts WebSocketManager thread
└── starts KtorServer
    ├── HTTPRoutes.configure(application)
    └── WebSocket route (/ws)
```

Notes:
- `KtorServer.kt` boots the server and WebSocket handling.
- `HTTPRoutes.kt` owns HTTP endpoint definitions.
- `APIService.kt` contains endpoint processing logic.

## Troubleshooting

### Port already in use

Change port in `application.conf`:

```conf
server {
  host = "127.0.0.1"
  port = 8081
}
```

### Build issues

```powershell
./gradlew clean build
```

### WebSocket connection fails

- Verify server is running
- Verify URL is `ws://127.0.0.1:8080/ws`
- Check firewall/local network policy

## Next Steps

1. Add new HTTP endpoints in `HTTPRoutes.kt`.
2. Keep business logic in `APIService.kt`.
3. Extend WebSocket message processing in `Main.kt`.
4. Keep `HTTP_ENDPOINTS.md` and `GETTING_STARTED.md` in sync.
