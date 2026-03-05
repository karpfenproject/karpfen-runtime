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

### Build the project

```powershell
./gradlew build
```

Output JAR: `build/libs/karpfen-runtime-1.0-SNAPSHOT.jar`

### Run the server

Using Gradle:
```powershell
./gradlew run
```

Or directly with Java:
```powershell
java -jar build/libs/karpfen-runtime-1.0-SNAPSHOT.jar
```

Default server URL: `http://127.0.0.1:8080`

### Stop the server

Press `Ctrl+C` to gracefully shutdown.

## Configuration

Runtime configuration is loaded from `application.conf` in the project root.

### Example configuration

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

### Configuration options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `server.host` | String | "127.0.0.1" | Server bind address |
| `server.port` | Int | 8080 | Server listen port |
| `websocket.enabled` | Boolean | true | Enable WebSocket broadcaster |
| `websocket.queueTimeoutMs` | Long | 1000 | Message queue timeout |
| `logging.level` | String | "INFO" | Log level (DEBUG, INFO, WARN, ERROR) |

## Main Components

### KtorServer
- Manages HTTP and WebSocket endpoints
- Handles client authentication via first WebSocket message
- Routes incoming WebSocket events to environment event queues

### APIService
- Implements business logic for all HTTP endpoints
- Validates environment state and parameters
- Manages environment lifecycle

### EnvironmentHandler
- Global singleton managing all environments
- Tracks active environments and their execution threads
- Manages client session lifecycle

### ClientSessionManager
- Maintains WebSocket client session registry
- Tracks object subscriptions per client
- Queues outgoing data change notifications

### EnvironmentThread
- Executes the Karpfen execution engine
- Processes incoming events from WebSocket message queue
- Triggers data change notifications to subscribed clients

### WebSocketBroadcaster
- Runs asynchronously in separate thread
- Processes outgoing message queue
- Ensures non-blocking message delivery to clients

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

This section demonstrates a complete workflow from environment creation to execution.

### 1. Create environment
```powershell
$envKey = $(curl -X POST http://localhost:8080/createEnvironment).Content
Write-Host "Created environment: $envKey"
```

### 2. Configure environment (metamodel, model, state machine)
```powershell
# Upload metamodel
curl -X PUT "http://localhost:8080/setMetamodel?envKey=$envKey" `
  -ContentType "text/plain" `
  -InFile metamodel.kmeta

# Upload model
curl -X PUT "http://localhost:8080/setModel?envKey=$envKey" `
  -ContentType "text/plain" `
  -InFile model.kmodel

# Upload state machine
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$envKey&attachedTo=robot1" `
  -ContentType "text/plain" `
  -InFile statemachine.kstates

# Set execution tick delay
curl -X POST "http://localhost:8080/setTickDelay?envKey=$envKey&milliseconds=100"
```

### 3. Register client for WebSocket
```powershell
$accessKey = $(curl -X POST "http://localhost:8080/registerClientForWebSocket?clientId=client1&envKey=$envKey").Content
Write-Host "Access key: $accessKey"
```

### 4. Register for notifications (optional)
```powershell
# Subscribe to object changes
curl -X POST "http://localhost:8080/registerObjectObserver?envKey=$envKey&clientId=client1&objectId=robot1"

# Subscribe to domain events
curl -X POST "http://localhost:8080/registerDomainListener?envKey=$envKey&clientId=client1&domain=robot_domain"
```

### 5. Start environment
```powershell
curl -X POST "http://localhost:8080/startEnvironment?envKey=$envKey"
Write-Host "Environment started"
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
```powershell
curl -X POST "http://localhost:8080/stopEnvironment?envKey=$envKey"
Write-Host "Environment stopped"
```

## Testing

### Run all tests
```powershell
./gradlew test
```

### Run specific test class
```powershell
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

Then rebuild and run.

### Build fails

Try clean rebuild:
```powershell
./gradlew clean build
```

### WebSocket connection fails

1. Verify server is running: `curl http://localhost:8080/health`
2. Check URL format: `ws://localhost:8080/ws` (not `http://`)
3. Ensure first message is authentication in format: `clientId:envKey:accessKey`
4. Check firewall/antivirus is not blocking port 8080

### Environment creation fails

Ensure the HTTP request is properly formatted:
```powershell
# Verify endpoint is accessible
curl http://localhost:8080/health

# Create environment
curl -X POST http://localhost:8080/createEnvironment
```

### Messages not received after WebSocket connection

1. Ensure environment has been started with `/startEnvironment`
2. Verify client is subscribed to correct domain/object
3. Check that execution engine is processing events (look at console output)

## Next Steps

- Review [HTTP_API_ENDPOINTS.md](HTTP_API_ENDPOINTS.md) for detailed endpoint documentation
- Add custom domain listeners for your specific event types
- Extend `APIService` with additional business logic
- Modify `application.conf` for your deployment environment

