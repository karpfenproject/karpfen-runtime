# Delivery Drone — Event Payloads & Scoped Events

A single autonomous delivery drone that is driven entirely by **events that carry structured payloads**.
This example showcases the advanced event-management feature set.

## Feature highlights

| Feature | Where to look |
|---------|---------------|
| Event payload definitions (incl. an embedded domain type) | [EVENTS.kmeta](EVENTS.kmeta) — `assignDelivery` carries a `GeoPoint` `destination` |
| `EVENT` clause + payload/model guards in one `CONDITION` | `idle -> cruise` guards on `$(event->priority)` and `$(battery->charge)` |
| Reading a scoped event payload in `ENTRY` | `throttle` reads `$(event->factor)`; `cruise` reads `$(event->destination->x)` |
| `IF IN SCOPE(...)` adapting to *how* a state was entered | `cruise` ENTRY captures the destination only when it arrived via `assignDelivery` |
| Persisting a payload so it outlives the event | `packageId` is copied into the model, still readable in `delivering` |

## Lifecycle

```
idle ──assignDelivery(priority>=1, battery>20)──▶ cruise ──(arrived)──▶ delivering ──▶ returning ──(home)──▶ idle
                                                   │  ▲
                                       setCruiseSpeed │  │ (plain)
                                                   ▼  │
                                                 throttle
                                                   │
                                          recall   │
        cruise ──────────────────────────────────▶ returning
```

The drone idles until it receives a delivery job. It only accepts jobs with `priority >= 1` and while its
battery is above 20% — both checked as guard clauses on the same transition. While flying it can be
re-throttled (`setCruiseSpeed`) or recalled (`recall`) at any time. When it reaches the destination it
"drops" the package, then flies home.

## Run it

```bash
ENV=$(curl -s -X POST http://localhost:8080/createEnvironment)

curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV"        -H "Content-Type: text/plain" --data-binary @delivery_drone.kmeta
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV"            -H "Content-Type: text/plain" --data-binary @delivery_drone.kmodel
curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=$ENV" -H "Content-Type: text/plain" --data-binary @EVENTS.kmeta
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV&attachedTo=scout" -H "Content-Type: text/plain" --data-binary @delivery_drone.kstates

curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV&milliseconds=300"

# Activate (required before start), then start the engine
curl -X POST "http://localhost:8080/runEnvironment?envKey=$ENV"
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV"
```

Then connect a WebSocket client (see [`../../guides/getting_started.md`](../../guides/getting_started.md)) and send events. `environmentKey` is the
**event domain** (`control`), `messageType` is the **event name / payload type**:

```jsonc
// assign a delivery to (12, 8)
{ "environmentKey": "control", "messageType": "assignDelivery",
  "payload": "{\"packageId\":\"PKG-42\",\"priority\":2,\"destination\":{\"x\":12.0,\"y\":8.0}}" }

// speed up mid-flight
{ "environmentKey": "control", "messageType": "setCruiseSpeed", "payload": "{\"factor\":3.0}" }

// abort and return home
{ "environmentKey": "control", "messageType": "recall", "payload": "" }
```

Watch the drone's `log` (subscribe with `registerObjectObserver ... objectId=scout`) to follow the run.
