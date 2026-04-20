# Getting Started Guide - Karpfen Runtime

Karpfen Runtime is a lightweight Kotlin/Ktor-based HTTP API server with WebSocket support for managing Karpfen DSL model execution environments. It is runtime-only (no database, no persistence layer) and operates entirely in-memory.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Build and Run](#build-and-run)
4. [Configuration](#configuration)
5. [Main Components](#main-components)
6. [API Overview](#api-overview)
7. [WebSocket Communication](#websocket-communication)
8. [Example Workflow](#example-workflow)
9. [Testing](#testing)
10. [Architecture](#architecture)
11. [Troubleshooting](#troubleshooting)

## Prerequisites

- Java 21 or higher
- Git

Gradle is included via wrapper (`gradlew` on Linux/Mac, `gradlew.bat` on Windows).

## Project Structure

```text
karpfen-runtime/
├── src/main/kotlin/io/karpfen/
│   ├── Main.kt                           # Application entry point
│   ├── config/
│   │   └── ApplicationConfig.kt          # Configuration loader
│   ├── server/
│   │   ├── KtorServer.kt                 # HTTP & WebSocket server
│   │   ├── HTTPRoutes.kt                 # HTTP endpoint definitions
│   │   └── APIService.kt                 # Business logic
│   ├── env/
│   │   ├── Environment.kt                # Environment data model
│   │   ├── EnvironmentHandler.kt         # Lifecycle management
│   │   ├── EnvironmentThread.kt          # Execution engine thread
│   │   ├── Observation.kt                # Object observation model
│   │   ├── DomainListener.kt             # Domain listener model
│   │   └── ActiveEnvironment.kt          # Active environment wrapper
│   └── websocket/
│       ├── WebSocketManager.kt           # (deprecated) Legacy WebSocket manager
│       ├── WebSocketMessage.kt           # Message data model
│       ├── ClientSession.kt              # Client session model
│       ├── ClientSessionManager.kt       # Session lifecycle management
│       └── WebSocketBroadcaster.kt       # Async message broadcaster
├── src/test/kotlin/io/karpfen/server/
│   └── KtorServerIntegrationTest.kt      # Integration tests
├── execution-engine/                     # Engine module (separate)
├── karpfen-dsl-tools/                    # DSL tools module (separate)
├── build.gradle                          # Main module config
├── application.conf                      # Server configuration
├── guides/
│   ├── INDEX.md
│   ├── QUICK_REFERENCE.md
│   ├── GETTING_STARTED.md                # This file
│   └── HTTP_API_ENDPOINTS.md
```

## Build and Run

The recommended way to start the server is via the provided shell scripts.  They handle the Gradle build step for you and require no manual `application.conf` editing - configure the file once, then just run the script.

### Option A – Local (native Java + Python)

**Prerequisites:** Java 21+, Python 3.12+

```bash
# from the karpfen-runtime/ directory
./run_local.sh

# or from the repository root
./run_local.sh
```

Builds the distribution with `./gradlew installDist` and starts `./build/install/karpfen-runtime/bin/karpfen-runtime` in the foreground. Press `Ctrl+C` to stop.

### Option B – Docker (Debian Trixie)

**Prerequisites:** Docker Engine (or Docker Desktop)

```bash
# from the karpfen-runtime/ directory (optional HOST_LOG_DIR argument)
./run_docker.sh [HOST_LOG_DIR]

# or from the repository root
./run_docker.sh [HOST_LOG_DIR]
```

Multi-stage build: a `eclipse-temurin:21-jdk` builder stage compiles the project; the resulting distribution is copied into a `debian:trixie-slim` runtime stage that installs only `openjdk-21-jre-headless` and `python3`.  The container is started with `--rm` and removed automatically on exit.

`HOST_LOG_DIR` is an optional host path where engine trace log files are written (default: `./logs` relative to `karpfen-runtime/`).  See [Trace Logging](#trace-logging) below.

### Manually (Gradle)

```bash
./gradlew build
./gradlew run
```

Direct JAR execution:
```bash
java -jar build/libs/karpfen-runtime-1.0-SNAPSHOT.jar
```

Default server URL: `http://127.0.0.1:8080`

### Stop the server

Press `Ctrl+C` to gracefully shutdown.

## Configuration

Runtime configuration is loaded from `application.conf` in the `karpfen-runtime/` directory.  Edit this file before starting the server - neither run script overwrites it.

### Full configuration reference

```conf
# ── Server ------------------------------------------------------------------
server {
  host = "127.0.0.1"
  port = 8080
}

# ── WebSocket ---------------------------------------------------------------
websocket {
  queueTimeoutMs = 1000
  enabled        = true
}

# ── Logging -----------------------------------------------------------------
logging {
  level         = "INFO"   # DEBUG | INFO | WARN | ERROR
  consoleOutput = true
}

# ── Engine ------------------------------------------------------------------
engine {
  defaultTickDelayMs = 1000
}

# ── Engine Tracing ----------------------------------------------------------
engineTracing {
  tracingEnabled      = false
  tracingLogDirectory = "logs"   # local: relative path; Docker: "/app/logs"
  tracingConsoleOutput = false
}
```

### Configuration options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `server.host` | String | `"127.0.0.1"` | Server bind address. `run_docker.sh` overrides this to `"0.0.0.0"` automatically; no manual change needed. |
| `server.port` | Int | `8080` | HTTP and WebSocket listen port. |
| `websocket.enabled` | Boolean | `true` | Enable WebSocket broadcaster. |
| `websocket.queueTimeoutMs` | Long | `1000` | Message queue drain timeout (ms). |
| `logging.level` | String | `"INFO"` | Log level: `DEBUG`, `INFO`, `WARN`, `ERROR`. |
| `logging.consoleOutput` | Boolean | `true` | Print log lines to stdout. |
| `engine.defaultTickDelayMs` | Int | `1000` | Default tick delay (ms) for new environments (overridable via `/setTickDelay`). |
| `engineTracing.tracingEnabled` | Boolean | `false` | Write per-environment execution trace log files. |
| `engineTracing.tracingLogDirectory` | String | `null` | Directory for trace log files (see below). |
| `engineTracing.tracingConsoleOutput` | Boolean | `false` | Also print trace lines to stdout. |

### Trace logging

When `tracingEnabled = true` the runtime writes one log file per environment to the directory given by `tracingLogDirectory`.

**Local run** - use a relative path (resolved from `karpfen-runtime/`):
```conf
engineTracing {
  tracingEnabled      = true
  tracingLogDirectory = "logs"
}
```

**Docker run** - set the path to the container-side mount point `/app/logs`.  `run_docker.sh` maps this to a host directory so that trace files survive after the container is removed:
```conf
engineTracing {
  tracingEnabled      = true
  tracingLogDirectory = "/app/logs"
}
```

Then start with a custom log location:
```bash
./run_docker.sh /path/to/my/traces
```

## Main Components

- **KtorServer** - HTTP + WebSocket server. Authenticates WebSocket clients on their first message and forwards incoming events to the right environment's event queue.
- **APIService** - Business logic behind the HTTP endpoints (parameter validation, environment lifecycle).
- **EnvironmentHandler** - Global singleton that owns all environments, their execution threads, and client sessions.
- **ClientSessionManager** - Keeps track of which WebSocket clients are subscribed to which objects/domains and queues outgoing notifications for them.
- **EnvironmentThread** - Runs the execution engine loop for one environment: polls the event queue, ticks the engine, and fires data-change callbacks.
- **WebSocketBroadcaster** - Separate thread that drains the outgoing message queue and pushes messages to connected WebSocket sessions without blocking the engine.

## API Overview

The API is organized into three categories:

### Environment Setup
- `POST /createEnvironment` - Create new environment
- `PUT /setMetamodel` - Upload metamodel
- `PUT /setModel` - Upload model instance
- `PUT /setStateMachine` - Upload state machine
- `POST /setTickDelay` - Configure execution tick delay

### Execution Control
- `POST /startEnvironment` - Start execution engine
- `POST /stopEnvironment` - Stop execution engine

### Client Registration
- `POST /registerClientForWebSocket` - Get WebSocket access key
- `POST /registerObjectObserver` - Subscribe to object changes
- `POST /registerDomainListener` - Subscribe to domain events

For complete endpoint reference with examples, see [HTTP_API_ENDPOINTS.md](HTTP_API_ENDPOINTS.md).

## WebSocket Communication

### Connection endpoint
```
ws://127.0.0.1:8080/ws
```

### Authentication (first message)
```
clientId:envKey:accessKey
```

Example:
```javascript
const clientId = "client1";
const envKey = "env-123";
const accessKey = "ak-client1-1234567890-5432";

const ws = new WebSocket('ws://localhost:8080/ws');
ws.onopen = () => {
    ws.send(`${clientId}:${envKey}:${accessKey}`);
};
```

### Message format (after authentication)
```json
{
  "environmentKey": "env-123",
  "messageType": "event_name",
  "payload": "message_content_or_json"
}
```

### Incoming notifications
Subscribed clients receive data change notifications:
```json
{
  "clientId": "client1",
  "messageType": "objectChanged",
  "payload": "{\"objectId\":\"obj1\",\"value\":...}"
}
```

## Example Workflow

### 1. Create environment
```bash
ENV_KEY=$(curl -s -X POST http://localhost:8080/createEnvironment)
echo "Created environment: $ENV_KEY"
```

### 2. Configure environment (metamodel, model, state machine)
```bash
# Upload metamodel
curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV_KEY" \
 -H "Content-Type: text/plain" \
 -d @metamodel.kmeta

# Upload model
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV_KEY" \
 -H "Content-Type: text/plain" \
 -d @model.kmodel

# Upload state machine
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV_KEY&attachedTo=robot1" \
 -H "Content-Type: text/plain" \
 -d @statemachine.kstates

# Set execution tick delay
curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV_KEY&milliseconds=100"
```

### 3. Register client for WebSocket
```bash
ACCESS_KEY=$(curl -s -X POST "http://localhost:8080/registerClientForWebSocket?clientId=client1&envKey=$ENV_KEY")
echo "Access key: $ACCESS_KEY"
```

### 4. Register for notifications (optional)
```bash
# Subscribe to object changes
curl -X POST "http://localhost:8080/registerObjectObserver?envKey=$ENV_KEY&clientId=client1&objectId=robot1"

# Subscribe to domain events
curl -X POST "http://localhost:8080/registerDomainListener?envKey=$ENV_KEY&clientId=client1&domain=robot_domain"
```

### 5. Start environment
```bash
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV_KEY"
echo "Environment started"
```

### 6. Connect via WebSocket and send messages
```javascript
const ws = new WebSocket('ws://localhost:8080/ws');

ws.onopen = () => {
    // Send authentication
    ws.send('client1:' + envKey + ':' + accessKey);
    
    // Send an event after a short delay
    setTimeout(() => {
        const message = {
            environmentKey: envKey,
            messageType: 'robot_command',
            payload: 'move_forward'
        };
        ws.send(JSON.stringify(message));
    }, 500);
};

ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    console.log('Received:', msg.messageType, msg.payload);
};
```

### 7. Stop environment when done
```bash
curl -X POST "http://localhost:8080/stopEnvironment?envKey=$ENV_KEY"
echo "Environment stopped"
```

## Testing

### Run all tests
```bash
./gradlew test
```

### Run specific test class
```bash
./gradlew test --tests KtorServerIntegrationTest
```

### View test report
```
build/reports/tests/test/index.html
```

## Architecture

### Startup sequence
```
Main.kt
├── Load ApplicationConfig from application.conf
├── Initialize EnvironmentHandler (singleton)
├── Initialize ClientSessionManager (singleton)
├── Start WebSocketBroadcaster thread
└── Start KtorServer
    ├── Configure HTTP routes
    └── Configure WebSocket endpoint
```

### Event flow for incoming WebSocket message
```
WebSocket /ws endpoint
├── Authenticate client (first message)
├── Parse incoming JSON message
├── Convert to Event object
└── Offer to EnvironmentThread.eventQueue

EnvironmentThread
├── Poll eventQueue periodically
├── Process event with execution engine
└── Trigger callbacks for data changes

ClientSessionManager
├── Receive data change notification
├── Find subscribed clients
└── Queue OutgoingMessage

WebSocketBroadcaster
├── Poll outgoingMessageQueue
├── Send to connected WebSocket sessions
└── Handle delivery errors
```

## Troubleshooting

### Port already in use

Change the port in `application.conf`:
```conf
server {
  port = 8081
}
```

Then restart the server.

### Build fails

Try clean rebuild:
```bash
./gradlew clean build
```

### WebSocket connection fails

1. Verify server is running: `curl http://localhost:8080/health`
2. Check URL format: `ws://localhost:8080/ws` (not `http://`)
3. Ensure first message is authentication in format: `clientId:envKey:accessKey`
4. Check firewall/antivirus is not blocking port 8080

### Environment creation fails

Ensure the HTTP request is properly formatted:
```bash
# Verify endpoint is accessible
curl http://localhost:8080/health

# Create environment
curl -X POST http://localhost:8080/createEnvironment
```

### Messages not received after WebSocket connection

1. Ensure environment has been started with `/startEnvironment`
2. Verify client is subscribed to correct domain/object
3. Check that execution engine is processing events (look at console output)

