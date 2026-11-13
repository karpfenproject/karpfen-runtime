# Drone swarm: adding, setting and removing objects and lists

A swarm coordinator grows, reshapes and shrinks a fleet of drones while it runs. The example uses every action that
changes objects and lists in one state machine.

## What it shows

| Manipulator | Relation kind | Where to look |
|-------------|---------------|---------------|
| `APPENDOBJ` (new object) | `has` list | `launching` adds a new `Drone` that `make_drone` builds |
| `SETOBJ` (existing object) | single `knows` | `forming` sets `leader` to the fittest drone |
| `APPENDOBJ` (existing object) | `knows` list | `forming` adds the weakest drone to `watchlist` |
| `SETLIST` | list property | `forming` and `maintenance` rebuild the `roster` |
| `SETOBJ` and `DROPOBJ` | single `knows`, then removal | `maintenance` sets `condemned` to the weakest drone, then removes that drone with every reference to it |
| `DROPLIST` | list property | `shutdown` clears the `roster` |
| `WITH ... AS` | embedded object | `operating` changes the values of `formationCenter` in place, so it keeps its id |

## Lifecycle

```text
standby ──deploy──▶ launching ──(fleet == targetSize)──▶ forming ──▶ operating
                       ▲                                                 │  │
                       │                                       droneDown │  │ recall
                       └────────────── maintenance ◀────────────────────┘  │
                                                                            ▼
                                                                         shutdown
```

- `launching` adds one new drone per tick, with `APPENDOBJ` of a new object into the `has("drones")` list, until
  the fleet reaches `targetSize`.
- `forming` picks a `leader` with `SETOBJ` to an existing drone, adds the weakest drone to the `watchlist` with
  `APPENDOBJ` of an existing object, and rebuilds the `roster` with `SETLIST`.
- `operating` moves the `formationCenter` each tick with a `WITH ... AS` block, which changes the values of the
  embedded `Vector` in place.
- `maintenance`, on `droneDown`, removes the weakest drone. It sets `condemned` to that drone with `SETOBJ` and
  removes it with `DROPOBJ("condemned")`. The drone leaves the `drones` list, and every `knows` reference to it
  (`leader`, `watchlist`, `condemned`) is removed. Then the state machine goes back to `launching` to fill the fleet
  again.
- `shutdown`, on `recall`, clears the roster with `DROPLIST`.

`make_drone` gives each drone a random battery level, so "fittest" and "weakest" pick different drones in different
runs. The macros search the current model and use no fixed ids.

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

Then connect a WebSocket client and send events. `environmentKey` is the domain of the event, `swarm`:

```jsonc
{ "environmentKey": "swarm", "messageType": "deploy",    "payload": "" }   // grow to targetSize, then patrol
{ "environmentKey": "swarm", "messageType": "droneDown", "payload": "" }   // retire the weakest, then replenish
{ "environmentKey": "swarm", "messageType": "recall",    "payload": "" }   // clear the roster and shut down
```

An observer registered with `registerObjectObserver ... objectId=alpha-swarm` before `runEnvironment` sees the roster and the log change.
When `DROPOBJ` removes a drone and the objects it holds, the runtime sends `objectDeleted` messages.
