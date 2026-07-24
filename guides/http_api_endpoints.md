# HTTP API Endpoints

This document describes all available HTTP API endpoints in the Karpfen Runtime Server.

## Environment Management

### Create Environment
- **Endpoint**: `POST /createEnvironment`
- **Parameters**: None
- **Request Body**: Empty
- **Response**: String (environment key)
- **Description**: Creates a new environment and returns its unique key for later use.
- **Example**:
  ```bash
  curl -X POST http://localhost:8080/createEnvironment
  # Returns: env-1609459200000
  ```

### Set Metamodel
- **Endpoint**: `PUT /setMetamodel`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Large string containing the metamodel definition
- **Response**: Empty (200 OK on success)
- **Description**: Uploads the metamodel for an environment. Must be called before setting the model.
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setMetamodel?envKey=env-123" \
    -H "Content-Type: text/plain" \
    -d @metamodel.kmeta
  ```

### Set Model
- **Endpoint**: `PUT /setModel`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Large string containing the model definition
- **Response**: Empty (200 OK on success)
- **Description**: Uploads the model instance for an environment. The metamodel must be set first.
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setModel?envKey=env-123" \
    -H "Content-Type: text/plain" \
    -d @model.kmodel
  ```

### Set Event Definitions
- **Endpoint**: `PUT /setEventDefinitions`
- **Parameters**:
  - `envKey` (string): The environment key
- **Request Body**: A kmeta document (conventionally `EVENTS.kmeta`) describing the event payloads
- **Response**: Empty (200 OK on success)
- **Description**: Registers the event payload types for an environment. Each type in the document is an event name; an event named `setSpeed` is parsed against the `setSpeed` type. This is optional — events without payloads work without it. Set the metamodel first if your event payloads embed or link domain types (such as an event carrying a `Vector`); those types are then resolvable from the event definitions.
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=env-123" \
    -H "Content-Type: text/plain" \
    -d @EVENTS.kmeta
  ```

### Set State Machine
- **Endpoint**: `PUT /setStateMachine`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `attachedTo` (string): The model element ID this state machine is attached to
- **Request Body**: Large string containing the state machine definition
- **Response**: Empty (200 OK on success)
- **Description**: Uploads a state machine that is attached to a specific model element.
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setStateMachine?envKey=env-123&attachedTo=robot1" \
    -H "Content-Type: text/plain" \
    -d @statemachine.kstates
  ```

### Set Tick Delay
- **Endpoint**: `POST /setTickDelay`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `milliseconds` (integer): Delay in milliseconds between execution ticks
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Sets the execution tick delay for the environment.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/setTickDelay?envKey=env-123&milliseconds=100"
  ```

## Execution Control

### Set Event TTL
- **Endpoint**: `POST /setEventTtl`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `ttlMs` (long): Time-to-live in milliseconds for events (0 = live forever)
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Sets the time-to-live for events in the environment. Events that exceed their TTL are automatically purged.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/setEventTtl?envKey=env-123&ttlMs=30000"
  ```

### Run Environment
- **Endpoint**: `POST /runEnvironment`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Activates the environment and creates its execution thread. Requires that the metamodel and model are set (a state machine is normally attached too, though not strictly enforced); otherwise returns `409 Conflict`. Must be called before `/startEnvironment`. Once activated, the environment can no longer be modified by the setup endpoints.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/runEnvironment?envKey=env-123"
  ```

### Start Environment
- **Endpoint**: `POST /startEnvironment`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Starts the execution thread for the selected environment. The environment must have been activated first using `/runEnvironment`.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/startEnvironment?envKey=env-123"
  ```

### Stop Environment
- **Endpoint**: `POST /stopEnvironment`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Stops the execution engine for the environment and closes all WebSocket connections.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/stopEnvironment?envKey=env-123"
  ```

### Set Active State
- **Endpoint**: `POST /setActiveState`
- **Parameters**:
  - `envKey` (string): The environment key
  - `modelElement` (string): The model element the target state machine is attached to
  - `state` (string): The leaf state to force the machine into
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Forces a running state machine into the given leaf state and clears its pending events
  (a manual "resync"). Unlike the setup endpoints, this requires the environment to be **active**
  (`/runEnvironment` already called). An unknown state for the model element returns `400 Bad Request`.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/setActiveState?envKey=env-123&modelElement=robot1&state=observe"
  ```

## Client Registration

### Register Client for WebSocket
- **Endpoint**: `POST /registerClientForWebSocket`
- **Parameters**: 
  - `clientId` (string): Unique identifier for the client
  - `envKey` (string): The environment key to connect to
- **Request Body**: Empty
- **Response**: String (access key)
- **Description**: Registers a client for WebSocket communication with an environment. Returns an access key needed for WebSocket authentication.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/registerClientForWebSocket?clientId=client1&envKey=env-123"
  # Returns: ak-client1-1609459234567-5432
  ```

### Register Object Observer
- **Endpoint**: `POST /registerObjectObserver`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `clientId` (string): The client ID
  - `objectId` (string): The object ID to observe
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Registers a client to receive notifications when a specific object changes.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/registerObjectObserver?envKey=env-123&clientId=client1&objectId=obj1"
  ```

### Register Domain Listener
- **Endpoint**: `POST /registerDomainListener`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `clientId` (string): The client ID
  - `domain` (string): The domain to listen to
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Registers a client to receive domain events from the execution engine.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/registerDomainListener?envKey=env-123&clientId=client1&domain=robot_events"
  ```

## Health Check

### Health
- **Endpoint**: `GET /health`
- **Parameters**: None
- **Request Body**: Empty
- **Response**: `200 OK` with `Content-Type: application/json` and body `{"status":"ok"}`.
- **Description**: Basic health check endpoint to verify the server is running. Useful as a readiness/liveness probe.
- **Example**:
  ```bash
  curl -i http://localhost:8080/health
  # HTTP/1.1 200 OK
  # Content-Type: application/json
  # {"status":"ok"}
  ```

## Observatory

Read-only endpoints for inspecting running environments (used by observatory/monitoring clients). They do
not modify execution state.

### List Active Environments
- **Endpoint**: `GET /observatory/environments`
- **Parameters**: None
- **Response**: JSON array of active environments, each with its key and the model elements that have a
  state machine attached.
- **Example**:
  ```bash
  curl http://localhost:8080/observatory/environments
  # Returns: [{"envKey":"env-123","modelElements":["robot1"]}]
  ```

### Get State Machine Source
- **Endpoint**: `GET /observatory/statemachine`
- **Parameters**:
  - `envKey` (string): The environment key
  - `modelElement` (string): The model element the state machine is attached to
- **Response**: String — the original `.kstates` source uploaded via `/setStateMachine`.
- **Description**: Returns `400 Bad Request` if no state machine source is registered for the element.
- **Example**:
  ```bash
  curl "http://localhost:8080/observatory/statemachine?envKey=env-123&modelElement=robot1"
  ```

### Register Observatory Client
- **Endpoint**: `POST /observatory/registerClient`
- **Parameters**:
  - `clientId` (string): Unique identifier for the client
  - `envKey` (string): The environment key
  - `modelElement` (string): The model element to observe
- **Response**: String (access key for WebSocket authentication)
- **Description**: Registers a client to receive observatory trace and state updates for a model element
  over WebSocket. Like `/registerClientForWebSocket`, the returned access key is used for the WebSocket
  authentication handshake.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/observatory/registerClient?clientId=monitor1&envKey=env-123&modelElement=robot1"
  ```

## WebSocket Connection

### WebSocket Endpoint
- **WebSocket URL**: `ws://localhost:8080/ws`
- **Description**: WebSocket connection for real-time communication between clients and environments.

#### Authentication
When connecting to the WebSocket, the first message must be an authentication message in the format:
```
clientId:envKey:accessKey
```

Where:
- `clientId`: The client identifier used for registration
- `envKey`: The environment key
- `accessKey`: The access key obtained from `/registerClientForWebSocket`

#### Message Format
After authentication, clients can send and receive messages as JSON:
```json
{
  "environmentKey": "env-123",
  "messageType": "eventType",
  "payload": "message content or data",
  "payloadFormat": "json"
}
```

- `environmentKey` is used as the event **domain** (the routing bucket a `EVENT("domain", ...)` condition listens on).
- `messageType` is the event **name**, and also the **payload type** it is parsed against (see Set Event Definitions).
- `payload` is the (optional) content. It may be empty, a JSON object, or a kmodel `make object` block.
- `payloadFormat` is optional and may be `"none"`, `"json"`, or `"kmodel"`. When omitted, the runtime guesses: empty → none, starts with `{` → json, starts with `make object` → kmodel.

## Error Handling

All endpoints return appropriate HTTP status codes:
- **200 OK**: Request successful
- **400 Bad Request**: Missing or invalid parameters
- **409 Conflict**: Environment state conflict (e.g., trying to modify active environment)
- **500 Internal Server Error**: Server error

Error responses include a message body with details about the error.
