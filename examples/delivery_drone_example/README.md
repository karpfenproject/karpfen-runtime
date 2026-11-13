# Delivery drone: events with payloads and scoped events

An autonomous delivery drone that is driven by events that carry payloads.

## What it shows

| Feature | Where to look |
|---------|---------------|
| Event definitions with payloads, one of them with a domain type | [EVENTS.kmeta](EVENTS.kmeta): `assignDelivery` carries a `GeoPoint` `destination` |
| An `EVENT` clause and clauses on the payload and the model in one `CONDITION` | `idle -> cruise` checks `$(event->priority)` and `$(battery->charge)` |
| Reading the payload of the scoped event in `ENTRY` | `throttle` reads `$(event->factor)`; `cruise` reads `$(event->destination->x)` |
| `IF IN SCOPE(...)` that depends on how a state was entered | The ENTRY block of `cruise` copies the destination only when `cruise` was entered with `assignDelivery` |
| Copying a payload value into the model, where it stays after the event | `packageId` is copied into the model and can still be read in `delivering` |

## Lifecycle

```text
idle ──assignDelivery(priority>=1, battery>20)──▶ cruise ──(arrived)──▶ delivering ──▶ returning ──(home)──▶ idle
                                                   │  ▲
                                       setCruiseSpeed │  │ (plain)
                                                   ▼  │
                                                 throttle
                                                   │
                                          recall   │
        cruise ──────────────────────────────────▶ returning
```

The drone waits in `idle` until it gets a delivery job. It takes a job only with `priority >= 1` and while its
battery is above 20%. Both are clauses of the condition of one transition. While it cruises, `setCruiseSpeed` changes
its speed and `recall` sends it home. At the destination it logs that it dropped the package, then flies
home.

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

Then connect a WebSocket client, see [3.5 WebSocket](../../docs/reference/3.5-websocket.md), and send events.
`environmentKey` is the domain of the event (`control`), and `messageType` is the name of the event and of its payload
type:

```jsonc
// assign a delivery to (12, 8)
{ "environmentKey": "control", "messageType": "assignDelivery",
  "payload": "{\"packageId\":\"PKG-42\",\"priority\":2,\"destination\":{\"x\":12.0,\"y\":8.0}}" }

// speed up mid-flight
{ "environmentKey": "control", "messageType": "setCruiseSpeed", "payload": "{\"factor\":3.0}" }

// abort and return home
{ "environmentKey": "control", "messageType": "recall", "payload": "" }
```

An observer registered with `registerObjectObserver ... objectId=scout` before `runEnvironment` sees the `log` of the drone change during the run.
