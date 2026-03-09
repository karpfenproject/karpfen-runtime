# Karpfen Runtime - Quick Reference Guide

## Project Completion Status: ✅ COMPLETE

The Karpfen Runtime server has been successfully implemented with full HTTP API and WebSocket support.

## 📦 Build Output

```
Build JAR: build/libs/karpfen-runtime-1.0-SNAPSHOT.jar
Build Status: SUCCESSFUL
All tests: PASSING
```

## 🚀 How to Run

### Local (native Java 21+ + Python 3.12+)

```bash
# from karpfen-runtime/ or repo root
./run_local.sh
```

### Docker (Debian Trixie)

```bash
# from karpfen-runtime/ or repo root
./run_docker.sh                     # logs → karpfen-runtime/logs/
./run_docker.sh /path/to/host/logs  # custom host log directory
```

Edit `karpfen-runtime/application.conf` before running either script — neither script overwrites it.

Default URLs:
- HTTP: `http://127.0.0.1:8080`
- WebSocket: `ws://127.0.0.1:8080/ws`

## 📋 Available HTTP Endpoints

| Method | Endpoint |
|--------|----------|
| POST | `/createEnvironment` |
| PUT | `/setMetamodel?envKey=...` |
| PUT | `/setModel?envKey=...` |
| PUT | `/setStateMachine?envKey=...&attachedTo=...` |
| POST | `/setTickDelay?envKey=...&milliseconds=...` |
| POST | `/registerObjectObserver?envKey=...&clientId=...&objectId=...` |
| POST | `/registerDomainListener?envKey=...&clientId=...&domain=...` |
| POST | `/registerClientForWebSocket?clientId=...&envKey=...` |
| POST | `/startEnvironment?envKey=...` |
| POST | `/stopEnvironment?envKey=...` |
| GET | `/health` |

## 🔗 WebSocket Connection

### Authentication Format (first message)
```
clientId:envKey:accessKey
```

### Message Format (after authentication)
```json
{
  "environmentKey": "env-123",
  "messageType": "event_type",
  "payload": "message_content"
}
```

## 🎯 Typical Workflow

### 1. Create Environment
```bash
curl -X POST http://localhost:8080/createEnvironment
# Response: env-1609459200000
```

### 2. Upload Configuration
```bash
# Metamodel
curl -X PUT "http://localhost:8080/setMetamodel?envKey=env-123" \
  -H "Content-Type: text/plain" \
  -d @metamodel.kmeta

# Model
curl -X PUT "http://localhost:8080/setModel?envKey=env-123" \
  -H "Content-Type: text/plain" \
  -d @model.kmodel

# State Machine
curl -X PUT "http://localhost:8080/setStateMachine?envKey=env-123&attachedTo=robot1" \
  -H "Content-Type: text/plain" \
  -d @statemachine.kstates
```

### 3. Register for WebSocket
```bash
curl -X POST "http://localhost:8080/registerClientForWebSocket?clientId=client1&envKey=env-123"
# Response: ak-client1-1609459234567-5432
```

### 4. Start Environment
```bash
curl -X POST "http://localhost:8080/startEnvironment?envKey=env-123"
```

### 5. Connect WebSocket and Send Messages
```javascript
const ws = new WebSocket('ws://localhost:8080/ws');

ws.onopen = () => {
    // Authenticate
    ws.send('client1:env-123:ak-client1-1609459234567-5432');
    
    // Send event
    setTimeout(() => {
        ws.send(JSON.stringify({
            environmentKey: 'env-123',
            messageType: 'robot_command',
            payload: 'move_forward'
        }));
    }, 500);
};

ws.onmessage = (event) => {
    console.log('Received:', event.data);
};
```

### 6. Stop Environment
```bash
curl -X POST "http://localhost:8080/stopEnvironment?envKey=env-123"
```

## 📊 Technical Stack

- **Language**: Kotlin
- **Framework**: Ktor 2.x
- **HTTP Server**: Netty
- **Build Tool**: Gradle
- **Java Target**: 21+
- **Testing**: JUnit 5

## 🐛 Troubleshooting

### Port Already in Use
Change port in `application.conf`:
```conf
server {
  port = 8081
}
```

### Docker: Port Not Reachable from Host
Set `server.host = "0.0.0.0"` in `application.conf` so the server binds to all interfaces inside the container.

### Docker: No Trace Files on Host
1. Confirm `tracingEnabled = true` and `tracingLogDirectory = "/app/logs"` in `application.conf`.
2. Confirm the `HOST_LOG_DIR` passed to `run_docker.sh` exists and is writable.
3. The `-v` volume mount uses absolute paths; `run_docker.sh` resolves the path automatically.

### Build Failures
```bash
./gradlew clean build
```

### WebSocket Connection Issues
1. Verify server running: `curl http://localhost:8080/health`
2. Check URL format: `ws://localhost:8080/ws`
3. First message must be: `clientId:envKey:accessKey`

---

**Last Updated**: 2026-03-05
**Build Status**: SUCCESSFUL
**Test Status**: PASSING
**Documentation**: COMPLETE

