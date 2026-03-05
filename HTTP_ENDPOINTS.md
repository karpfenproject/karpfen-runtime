# HTTP Endpoint Documentation

This document describes the HTTP API exposed by `KtorServer` in `src/main/kotlin/io/karpfen/server/KtorServer.kt`.

## Error handling

- Success: `200 OK`
- Error: status code depends on exception type, response body contains the exception message
  - `IllegalArgumentException` -> `400 Bad Request`
  - `IllegalStateException` -> `409 Conflict`
  - any other exception -> `500 Internal Server Error`

## Endpoints

### `POST /createEnvironment`

Creates a new environment and returns its key.

- Query params: none
- Body: none
- Success response: `200 OK` with plain text `envKey`
- Backing service method: `APIService.createEnvironment()`

Example:

```powershell
curl -X POST "http://127.0.0.1:8080/createEnvironment"
```

---

### `PUT /setMetamodel`

Sets metamodel text for an environment.

- Query params:
  - `envKey` (string, required)
- Body: metamodel content (plain text, potentially large)
- Success response: `200 OK` with empty body
- Backing service method: `APIService.receiveMetamodel(envKey, metamodelData)`

Example:

```powershell
curl -X PUT "http://127.0.0.1:8080/setMetamodel?envKey=env-123" -H "Content-Type: text/plain" -d "<kmeta text>"
```

---

### `PUT /setModel`

Sets model text for an environment.

- Query params:
  - `envKey` (string, required)
- Body: model content (plain text, potentially large)
- Success response: `200 OK` with empty body
- Backing service method: `APIService.receiveModel(envKey, modelData)`

Example:

```powershell
curl -X PUT "http://127.0.0.1:8080/setModel?envKey=env-123" -H "Content-Type: text/plain" -d "<kmodel text>"
```

---

### `PUT /setStateMachine`

Sets state machine text for a model element in an environment.

- Query params:
  - `envKey` (string, required)
  - `attachedTo` (string, required)
- Body: state machine content (plain text, potentially large)
- Success response: `200 OK` with empty body
- Backing service method: `APIService.receiveStateMachine(envKey, attachedTo, stateMachineData)`

Example:

```powershell
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=env-123&attachedTo=MyObject" -H "Content-Type: text/plain" -d "<kstates text>"
```

---

### `POST /setTickDelay`

Updates tick delay in milliseconds for an environment.

- Query params:
  - `envKey` (string, required)
  - `milliseconds` (int, required)
- Body: none
- Success response: `200 OK` with empty body
- Backing service method: `APIService.updateTickDelay(envKey, tickDelayMS)`

Example:

```powershell
curl -X POST "http://127.0.0.1:8080/setTickDelay?envKey=env-123&milliseconds=250"
```

---

### `POST /registerObjectObserver`

Registers an object observer.

- Query params:
  - `envKey` (string, required)
  - `clientId` (string, required)
  - `objectId` (string, required)
- Body: none
- Success response: `200 OK` with empty body
- Backing service method: `APIService.addObjectObservation(envKey, clientId, objectId)`

Example:

```powershell
curl -X POST "http://127.0.0.1:8080/registerObjectObserver?envKey=env-123&clientId=client-a&objectId=obj-42"
```

---

### `POST /registerDomainListener`

Registers a domain listener.

- Query params:
  - `envKey` (string, required)
  - `clientId` (string, required)
  - `domain` (string, required)
- Body: none
- Success response: `200 OK` with empty body
- Backing service method: `APIService.addDomainListener(envKey, clientId, domain)`

Example:

```powershell
curl -X POST "http://127.0.0.1:8080/registerDomainListener?envKey=env-123&clientId=client-a&domain=orders"
```

---

### `GET /health`

Health check endpoint.

- Success response: `200 OK` with JSON `{"status":"ok"}`

