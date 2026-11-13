# HTTP API Endpoints

This document describes all available HTTP API endpoints in the Karpfen Runtime Server.

## Environment Management

### Create Environment
- **Endpoint**: `POST /createEnvironment`
- **Parameters**: None
- **Request Body**: Empty
- **Response**: String (environment key)
- **Description**: Creates a new environment and returns its unique key for later use. Keys have the shape
  `env-<epoch millis>` and are never handed out twice: when the key of the current millisecond is taken, the next
  free millisecond is used.
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
- **Description**: Uploads the metamodel for an environment. Must be called before setting the model. The text is
  read with the KMeta reader of karpfen-dsl-tools; see [Rejected uploads](#rejected-uploads).
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setMetamodel?envKey=env-123" \
    -H "Content-Type: text/plain" \
    --data-binary @metamodel.kmeta
  ```
- **Rejection**: `400 Bad Request` for a text with errors, for example a property of an unknown type in line 2 and a
  relation to a type that is not defined in line 6:
  ```text
  Upload of the metamodel rejected: 2 errors
  2:16: Unknown type 'decimal' of prop 'x'; a prop is of type 'string', 'number', 'boolean' (kmeta.unknown-property-type)
  6:22: Unknown class type 'Place' of has 'position' in type 'Robot' (kmeta.unknown-target-type)
  ```

### Set Model
- **Endpoint**: `PUT /setModel`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Large string containing the model definition
- **Response**: Empty (200 OK on success)
- **Description**: Uploads the model instance for an environment. The metamodel must be set first; without it the
  upload is answered with `409 Conflict`. The text is read with the KModel reader of karpfen-dsl-tools against the
  metamodel of the environment; see [Rejected uploads](#rejected-uploads).
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setModel?envKey=env-123" \
    -H "Content-Type: text/plain" \
    --data-binary @model.kmodel
  ```
- **Rejection**: `400 Bad Request` for a text with errors, for example a number property given a word in line 2 and
  a property the type does not define in line 3:
  ```text
  Upload of the model rejected: 2 errors
  2:23: 'fast' is not a number, but prop 'speed' is of type 'number' (kmodel.invalid-number)
  3:11: Class type 'Robot' has no property 'colour' (object 'turtle') (kmodel.unknown-property)
  ```

### Set Event Definitions
- **Endpoint**: `PUT /setEventDefinitions`
- **Parameters**:
  - `envKey` (string): The environment key
- **Request Body**: A kmeta document (conventionally `EVENTS.kmeta`) describing the event payloads
- **Response**: Empty (200 OK on success)
- **Description**: Registers the event payload types for an environment. Each type in the document is an event name; an event named `setSpeed` is parsed against the `setSpeed` type. This is optional: events without payloads work without it. Set the metamodel first if your event payloads embed or link domain types (such as an event carrying a `Vector`); those types are then resolvable from the event definitions. The text is read with the KMeta reader of karpfen-dsl-tools, with the types of the metamodel as base types; see [Rejected uploads](#rejected-uploads).
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=env-123" \
    -H "Content-Type: text/plain" \
    --data-binary @EVENTS.kmeta
  ```
- **Rejection**: `400 Bad Request` for a text with errors, for example a property of an unknown type in line 2 and a
  relation to a type that neither the event definitions nor the metamodel define in line 6:
  ```text
  Upload of the event definitions rejected: 2 errors
  2:21: Unknown type 'fraction' of prop 'factor'; a prop is of type 'string', 'number', 'boolean' (kmeta.unknown-property-type)
  6:22: Unknown class type 'Place' of has 'position' in type 'teleport' (kmeta.unknown-target-type)
  ```

### Set State Machine
- **Endpoint**: `PUT /setStateMachine`
- **Parameters**: 
  - `envKey` (string): The environment key
  - `attachedTo` (string): The model element ID this state machine is attached to
- **Request Body**: Large string containing the state machine definition
- **Response**: Empty (200 OK on success)
- **Description**: Uploads a state machine that is attached to a specific model element. The text is read with the
  KStates reader of karpfen-dsl-tools, with every optional language feature allowed; see
  [Rejected uploads](#rejected-uploads).
- **Example**:
  ```bash
  curl -X PUT "http://localhost:8080/setStateMachine?envKey=env-123&attachedTo=robot1" \
    -H "Content-Type: text/plain" \
    --data-binary @statemachine.kstates
  ```
- **Rejection**: `400 Bad Request` for a text with errors, for example a second state named `idle` in line 5 and a
  transition to a state that does not exist in line 9, uploaded with `attachedTo=turtle`:
  ```text
  Upload of the state machine for 'turtle' rejected: 2 errors
  5:16: There is already a state named 'idle' in line 3; every state needs a name of its own (kstates.duplicate-state)
  9:31: No state is named 'stop'; check the spelling or define the state (kstates.unknown-state)
  ```

### Rejected uploads

The four upload routes read their text with the readers of karpfen-dsl-tools, which report every problem of a text
with its place instead of stopping at the first one. An upload whose text has at least one error is refused with
`400 Bad Request`, and the environment stays as it was: a metamodel, model, set of event definitions or state machine
uploaded before is kept. The body is plain text:

- The first line names the upload and counts its errors: `Upload of the <upload> rejected: <n> errors`, with
  `1 error` for a single one. The upload is `metamodel`, `model`, `event definitions` or
  `state machine for '<attachedTo>'`.
- Then one line per error, in the order of the text: `<line>:<column>: <message> (<code>)`. Line and column are
  counted from 1 and give the start of the offending part; the column counts UTF-16 characters, so a character
  outside the Basic Multilingual Plane counts twice. A line break inside a message is written as a space.
- The lines are separated by a line feed, and the body does not end with one.

The message is meant for a human reader and may change between versions of karpfen-dsl-tools; the code is stable.
The [verification guide](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/guides/verification.md) of
karpfen-dsl-tools lists every code. Warnings do not reject an upload; the runtime logs each one on its console with
its place in the text.

Send the file with `--data-binary`, as in the examples: `curl -d @file` drops the line breaks, which moves every
error to line 1 and turns a `//` comment into the end of the whole text.

A missing `envKey` or `attachedTo` and an unknown environment are answered with `400` and a one-line message, an
environment that is already active with `409`, before the text is read.

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
- **Description**: Activates the environment and creates its execution thread. Requires that the metamodel and model are set (a state machine is normally attached too, though not strictly enforced); otherwise returns `409 Conflict`. Every state machine must be attached to an object of the model (nested objects included); otherwise the call answers `409 Conflict` naming the missing keys and the environment stays configurable. Must be called before `/startEnvironment`. Once activated, the environment can no longer be modified by the setup endpoints. An environment is activated once: a second call answers `409 Conflict` (`... is already active ...`) and the first execution thread stays in charge; to start an environment again after a stop, call `/startEnvironment` alone.
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
- **Description**: Starts the execution thread for the selected environment. The environment must have been activated first using `/runEnvironment`. Calling it while the environment runs has no effect, so it is safe to repeat. Calling it after `/stopEnvironment` runs the environment again from the initial states over the model as the engine left it.
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
- **Description**: Stops the execution engine for the environment and closes the environment's WebSocket connections with the close code `1000` and the reason `Environment stopped`. Messages still queued for the environment are dropped. The final model stays readable through `GET /observatory/model` (phase `stopped`).
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
- **Description**: Registers a client to receive notifications when a specific object changes. Observers are registered before `/runEnvironment` only; activation freezes them. To watch the whole object tree at any time, including objects created at run time, use the model subscription (`POST /observatory/subscribeModel`).
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
- **Description**: Registers a client to receive domain events from the execution engine. Listeners are registered before `/runEnvironment` only. Every event published in the domain, whether sent by a client or raised by an `EVENT` action, is announced as a `domainEvent` message whose payload is `{"domain": <domain>, "payload": {"event", "payload", "payloadFormat", "source", "timestamp", "ttlMs"}}`: the event name, the raw payload text, its format (`none`, `json` or `kmodel`), its source (`INTERNAL`, `EXTERNAL_ENGINE` or `EXTERNAL_MESSAGE`), the receipt or raise time in epoch milliseconds, and the effective time to live.
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

## Model Observation

Endpoints for clients that mirror a whole environment, such as the karpfen IDE. They work in every phase of an
environment, also while it runs, and are implemented in the package `io.karpfen.observation`
(`ModelObservationRoutes`, `ModelSnapshotService`, `ModelSubscriptionService`).

### Get Model Snapshot
- **Endpoint**: `GET /observatory/model`
- **Parameters**:
  - `envKey` (string): The environment key
- **Response**: `200 OK` with `Content-Type: application/json; charset=UTF-8` and one JSON object with the members in
  this order:
  - `environmentKey`: the environment
  - `phase`: `configuring` (not yet run), `activated` (run, never started), `running` (the engine thread is alive,
    also while a start or a stop is under way), `halted` (started, not stopped, but the engine thread, or the event
    loop that hands client events to the engine, ended on its own after an error) or `stopped` (stopped, and the
    engine's last tick has ended)
  - `revision`: the revision of the last change the snapshot contains, `0` before anything happened
  - `activeStates`: per state machine (keyed by `attachedTo`) the last reported active stack, `{}` before the first
    tick after a start
  - `roots`: every root object of the model, each written exactly like the `value` of an `objectChanged` message
- **Description**: A running environment is read between two engine ticks, so the snapshot at revision R contains
  exactly the changes up to R. The runtime waits at most 10 seconds for a tick to end, on a thread for blocking calls
  rather than the server's event loop, so a long tick holds up no other request. A configuring environment answers its
  uploaded model at revision 0; a stopped one answers its final model.
- **Errors**: `400` for a missing `envKey` or an unknown environment; `409` with
  `Environment with key <envKey> has no model` (configuring, no model uploaded yet),
  `Environment with key <envKey> did not reach a tick boundary within 10000 ms`, or
  `Reference loop through object '<id>' cannot be written as JSON` (a `knows` loop has no JSON).
- **Example**:
  ```bash
  curl "http://localhost:8080/observatory/model?envKey=env-123"
  # {"environmentKey":"env-123","phase":"running","revision":57,"activeStates":{"robot1":["observe"]},"roots":[{"__id__": "room", ...}]}
  ```

### Subscribe to the Model
- **Endpoint**: `POST /observatory/subscribeModel`
- **Parameters**:
  - `envKey` (string): The environment key
  - `clientId` (string): The client ID
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: From now on the client receives `objectChanged` and `objectDeleted` for every object of the
  environment, objects created at run time included, and `stateChange` for every state machine. Unlike object
  observers it works at any time, also while the environment runs, and it is idempotent. The client must have an open,
  authenticated WebSocket session for the environment: the WebSocket handshake is never acknowledged, so a successful
  subscribe is the moment a client knows its socket is attached. The subscription is keyed by environment and client
  ID and survives reconnects.
- **Errors**: `400` for a missing `envKey` or `clientId` (checked in this order) or an unknown environment; `409` with
  `Client <clientId> has no open WebSocket session for environment <envKey>`.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/observatory/subscribeModel?envKey=env-123&clientId=client1"
  ```

### Unsubscribe from the Model
- **Endpoint**: `POST /observatory/unsubscribeModel`
- **Parameters**:
  - `envKey` (string): The environment key
  - `clientId` (string): The client ID
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: No new object or stack messages are queued for the client because of its model subscription;
  messages queued before may still arrive. Object observers and an observatory registration of the client stay.
  Idempotent.
- **Errors**: `400` for a missing `envKey` or `clientId` or an unknown environment.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/observatory/unsubscribeModel?envKey=env-123&clientId=client1"
  ```

### Switching observation on without losing changes
1. Authenticate the client's WebSocket.
2. Subscribe to the model. A `409` about a missing session means the handshake was not processed yet: wait and retry.
3. From the `200` on, keep every incoming object and stack message.
4. Get a snapshot; its revision is R. Replace the local copy with its `roots` and `activeStates`.
5. Apply the kept and all later messages in arrival order, skipping those with a revision of R or less. A revision
   larger than the last applied one plus 1 means messages were lost: take a new snapshot.

## Features

Histories, tick limits, breakpoints, stepping and event replay are features that each environment switches on and
off. The [features guide](features.md) describes them and their messages.

### List Registered Features
- **Endpoint**: `GET /getRegisteredFeatures`
- **Parameters**: None
- **Response**: String, the names of all features separated by commas
- **Description**: Lists the features the runtime knows.

### Activate Feature
- **Endpoint**: `POST /activateFeature`
- **Parameters**:
  - `envKey` (string): The environment key
  - `featureName` (string): The name of the feature
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Switches the feature on in the environment, together with the features it needs. Works from the
  creation of the environment on, also while it runs. Answers `400` for an unknown environment or feature and for a
  feature that is on already.

### List Active Features
- **Endpoint**: `GET /getActiveFeatures`
- **Parameters**:
  - `envKey` (string): The environment key
- **Response**: String, the names of the features that are on, separated by commas
- **Description**: Lists the features of the environment, also those that were switched on because another one needs
  them.

### Deactivate Feature
- **Endpoint**: `POST /deactivateFeature`
- **Parameters**:
  - `envKey` (string): The environment key
  - `featureName` (string): The name of the feature
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Switches the feature off, together with every feature that needs it. Answers `400` for an unknown
  environment or feature and for a feature that is not on.

### Send Message to Feature
- **Endpoint**: `POST /sendMessageToFeature`
- **Parameters**:
  - `envKey` (string): The environment key
  - `featureName` (string): The name of the feature
- **Request Body**: The message, as the features guide lists them for each feature
- **Response**: String, the answer of the feature
- **Description**: Hands the message to the feature. Answers `400` for an unknown environment or feature, for a
  feature that is not on, and for a message `TickByTick` does not know.
- **Example**:
  ```bash
  curl -X POST "http://localhost:8080/sendMessageToFeature?envKey=env-123&featureName=TickByTick" -d "tick:1"
  # Returns: execution is advancing 1 tick
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
- `payload` is the (optional) content. It may be empty, a JSON object, or a kmodel `make object` block. A payload
  that cannot be parsed against its type, also one nested too deeply to parse, leaves the event without a payload
  object; the event still fires by name.
- `payloadFormat` is optional and may be `"none"`, `"json"`, or `"kmodel"`. When omitted, the runtime guesses: empty → none, starts with `{` → json, starts with `make object` → kmodel.

#### Outgoing Messages
The runtime sends every notification as one text frame:
```json
{"environmentKey":"env-123","clientId":"client1","messageType":"objectChanged","payload":{"objectId":"robot1","revision":58,"value":{"__id__": "robot1", "__type__": "Robot", "x": 5}}}
```

| `messageType` | `payload` | Sent to |
|---|---|---|
| `objectChanged` | `{"objectId", "revision", "value"}` in this order; `value` is the object's JSON | its object observers and the model subscribers |
| `objectDeleted` | `{"objectId", "revision"}` in this order | the same |
| `stateChange` | `{"stack", "modelElementId", "revision"}`, member order not defined | observatory clients of `*` or that element, and the model subscribers |
| `domainEvent` | `{"domain", "payload"}`, see Register Domain Listener | the domain listeners of that domain |
| `engineStatus` | `{"paused", "waiting"}` and, while the engine waits at a breakpoint, `"halt"`, member order not defined; the same object as the answer to `getStatus` of the `Breakpoint` feature | every client connected to the environment |

Each environment numbers the object changes, object deletions and stack changes of its engine: the first one has
revision 1, every recipient of one change gets the same revision, and a client subscribed to the model receives them
in order without gaps. The one exception is a change to an object that cannot be written as JSON because of a
reference loop: it uses up its revision without a message, so the gap tells the client to take a snapshot, which
answers `409` while the loop exists. Domain events and engine statuses carry no revision. A client never gets the same change twice, even when it is
both an observer and a subscriber. All strings in a message are escaped as JSON.

`engineStatus` is sent only while the feature `TickByTick` is on, alone or with `Breakpoint`: once when the engine
thread starts to wait, between two ticks or at a breakpoint, and once when it goes on. It reaches every client with a
connected WebSocket, whatever it registered or subscribed for, and each client once. The
[features guide](features.md#breakpoint) describes it with an example.

## Error Handling

All endpoints return appropriate HTTP status codes:
- **200 OK**: Request successful
- **400 Bad Request**: Missing or invalid parameters, and uploaded texts with errors (see [Rejected uploads](#rejected-uploads))
- **409 Conflict**: Environment state conflict, for example:
  - modifying or running an environment that is already active
  - running an environment whose state machines are attached to objects missing from its model
  - starting or stopping an environment that was never run
  - a snapshot of an environment without a model, without a tick boundary within 10 seconds, or with a reference loop
  - subscribing to the model without an open WebSocket session
- **500 Internal Server Error**: Server error

Error responses include a message body with details about the error.
