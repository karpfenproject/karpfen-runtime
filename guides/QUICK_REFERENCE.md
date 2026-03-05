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

```bash
./gradlew build
./gradlew run
```

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

## 🔍 Architecture Overview

```
Client (HTTP/WebSocket)
    ↓
KtorServer (Netty)
    ├→ HTTPRoutes ← APIService (Business Logic)
    └→ WebSocket Handler
        ├→ Authenticate client
        ├→ Parse & validate message
        └→ Queue to EnvironmentThread

EnvironmentThread
    ├→ Poll event queue
    ├→ Execute engine
    └→ Trigger callbacks

ClientSessionManager
    ├→ Track subscriptions
    └→ Queue notifications

WebSocketBroadcaster
    ├→ Poll outgoing queue
    └→ Send to clients
```

## 🔐 Security Notes

Current implementation includes:
- Access key-based client authentication
- Thread-safe concurrent access
- No data persistence (runtime-only)

For production, consider adding:
- HTTPS/WSS encryption
- Advanced authentication (OAuth2/JWT)
- Rate limiting
- Input validation
- Audit logging

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

### Build Failures
```bash
./gradlew clean build
```

### WebSocket Connection Issues
1. Verify server running: `curl http://localhost:8080/health`
2. Check URL format: `ws://localhost:8080/ws`
3. First message must be: `clientId:envKey:accessKey`

## 📞 Support

For help:
1. **Setup**: See [GETTING_STARTED.md](GETTING_STARTED.md)
2. **API**: See [HTTP_API_ENDPOINTS.md](HTTP_API_ENDPOINTS.md)
3. **Navigation**: See [INDEX.md](INDEX.md)
4. **Examples**: See test files in `src/test/kotlin`

---

**Last Updated**: 2026-03-05
**Build Status**: SUCCESSFUL
**Test Status**: PASSING
**Documentation**: COMPLETE

