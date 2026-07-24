# Drone Swarm — Full Model Manipulators

A swarm coordinator that **grows, reshapes and shrinks a fleet of drones at runtime**. This example
exercises every model-manipulation operation in one coherent lifecycle.

## Feature highlights

| Manipulator | Relation kind | Where to look |
|-------------|---------------|---------------|
| `APPENDOBJ` (new object) | `has` list | `launching` spawns a fresh `Drone` built by `make_drone` |
| `SETOBJ` (existing object) | atomic `knows` | `forming` assigns `leader` to the fittest drone |
| `APPENDOBJ` (existing object) | `knows` list | `forming` adds the weakest drone to `watchlist` |
| `SETLIST` | simple list | `forming` / `maintenance` rebuild the `roster` |
| `SETOBJ` + `DROPOBJ` | atomic `knows` → removal | `maintenance` points `condemned` at the weakest drone, then drops it (cascade) |
| `DROPLIST` | simple list | `shutdown` clears the `roster` |
| `WITH ... AS` | embedded object | `operating` updates `formationCenter` value-wise, preserving its id |

## Lifecycle

```
standby ──deploy──▶ launching ──(fleet == targetSize)──▶ forming ──▶ operating
                       ▲                                                 │  │
                       │                                       droneDown │  │ recall
                       └────────────── maintenance ◀────────────────────┘  │
                                                                            ▼
                                                                         shutdown
```

- **launching** appends one brand-new drone per tick (`APPENDOBJ` of a freshly-built object into the
  `has("drones")` list) until the fleet reaches `targetSize`.
- **forming** picks a `leader` (`SETOBJ` to an existing drone), adds the weakest unit to the `watchlist`
  (`APPENDOBJ` of an existing reference) and rebuilds the `roster` (`SETLIST`).
- **operating** drifts the `formationCenter` each tick using a `WITH ... AS` block, which updates the
  embedded `Vector` in place instead of replacing it.
- **maintenance** (on `droneDown`) retires the weakest drone: it `SETOBJ`s `condemned` to that drone and
  `DROPOBJ`s through it. The drop cascades — the drone leaves the `drones` list and every inbound `knows`
  reference (`leader` / `watchlist` / `condemned`) is scrubbed. Then it relaunches to replenish.
- **shutdown** (on `recall`) clears the roster with `DROPLIST`.

Each `make_drone` gets a random battery level, so "fittest" and "weakest" pick out different units across
runs — the macros query the live model rather than relying on hard-coded ids.

## Run it

```bash
ENV=$(curl -s -X POST http://localhost:8080/createEnvironment)

curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV"        -H "Content-Type: text/plain" --data-binary @drone_swarm.kmeta
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV"            -H "Content-Type: text/plain" --data-binary @drone_swarm.kmodel
curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=$ENV" -H "Content-Type: text/plain" --data-binary @EVENTS.kmeta
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV&attachedTo=alpha-swarm" -H "Content-Type: text/plain" --data-binary @drone_swarm.kstates

curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV&milliseconds=300"

# Activate (required before start), then start the engine
curl -X POST "http://localhost:8080/runEnvironment?envKey=$ENV"
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV"
```

Then connect a WebSocket client and drive the swarm (`environmentKey` is the event domain, `swarm`):

```jsonc
{ "environmentKey": "swarm", "messageType": "deploy",    "payload": "" }   // grow to targetSize, then patrol
{ "environmentKey": "swarm", "messageType": "droneDown", "payload": "" }   // retire the weakest, then replenish
{ "environmentKey": "swarm", "messageType": "recall",    "payload": "" }   // clear the roster and shut down
```

Subscribe with `registerObjectObserver ... objectId=alpha-swarm` to watch the roster and log change, and
note the `objectDeleted` notifications emitted when `DROPOBJ` removes a drone and its embedded subtree.
