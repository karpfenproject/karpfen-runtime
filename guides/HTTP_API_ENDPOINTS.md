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

### Start Environment
- **Endpoint**: `POST /startEnvironment`
- **Parameters**: 
  - `envKey` (string): The environment key
- **Request Body**: Empty
- **Response**: Empty (200 OK on success)
- **Description**: Starts the execution thread for the selected environment.
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
- **Response**: JSON object with status
- **Description**: Basic health check endpoint to verify server is running.
- **Example**:
  ```bash
  curl http://localhost:8080/health
  # Returns: {"status":"ok"}
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
  "payload": "message content or data"
}
```

## Error Handling

All endpoints return appropriate HTTP status codes:
- **200 OK**: Request successful
- **400 Bad Request**: Missing or invalid parameters
- **409 Conflict**: Environment state conflict (e.g., trying to modify active environment)
- **500 Internal Server Error**: Server error

Error responses include a message body with details about the error.

## Example Workflow

1. Create an environment:
   ```bash
   envKey=$(curl -X POST http://localhost:8080/createEnvironment)
   ```

2. Upload metamodel, model, and state machines:
   ```bash
   curl -X PUT "http://localhost:8080/setMetamodel?envKey=$envKey" -d @metamodel.kmeta
   curl -X PUT "http://localhost:8080/setModel?envKey=$envKey" -d @model.kmodel
   curl -X PUT "http://localhost:8080/setStateMachine?envKey=$envKey&attachedTo=elem1" -d @statemachine.kstates
   ```

3. Register a client for WebSocket:
   ```bash
   accessKey=$(curl -X POST "http://localhost:8080/registerClientForWebSocket?clientId=client1&envKey=$envKey")
   ```

4. Register for notifications:
   ```bash
   curl -X POST "http://localhost:8080/registerObjectObserver?envKey=$envKey&clientId=client1&objectId=obj1"
   curl -X POST "http://localhost:8080/registerDomainListener?envKey=$envKey&clientId=client1&domain=domain1"
   ```

5. Start the environment:
   ```bash
   curl -X POST "http://localhost:8080/startEnvironment?envKey=$envKey"
   ```

6. Connect via WebSocket and send messages:
   ```
   ws = new WebSocket('ws://localhost:8080/ws')
   ws.onopen = () => {
     ws.send('client1:' + envKey + ':' + accessKey)
     ws.send('{"environmentKey":"'+ envKey +'","messageType":"event","payload":"data"}')
   }
   ```

7. Stop the environment when done:
   ```bash
   curl -X POST "http://localhost:8080/stopEnvironment?envKey=$envKey"
   ```
