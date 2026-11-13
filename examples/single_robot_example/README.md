# Single robot: a walkthrough

A cleaning robot, `turtle`, drives around a room, finds the closest obstacle and the closest wall, and steers away
from whichever is too close. The example uses most constructs of KStates in one state machine. Start with it before
the other examples, which each show fewer constructs.

## What it shows

| Feature | Where to look |
|---------|---------------|
| Nested states | `observe` holds `react to obstacle` and `react to wall`; `drive` holds `drive fast` and `drive slow` |
| `INITIAL` state and an event that starts the run | `ready` waits for `EVENT("public", "start")` before it does anything |
| `NOT LOOPING` transitions and transitions without a condition as fallbacks | `observe -> react to ...` and `drive -> drive fast/slow` are `NOT LOOPING`; `observe -> drive` and `drive -> observe` are the fallbacks |
| `EVAL` conditions on model values | `drive -> drive slow/fast` depend on `$(d_closest_obstacle)` and `$(d_closest_wall)` |
| `SETOBJ` on a `knows` relation | `observe` sets `closest_obstacle` and `closest_wall` to existing objects that macros return |
| `SET` with a value from a `MACRO` | `observe` writes the `d_closest_*` distances |
| `WITH ... AS` that changes the values of an embedded (`has`) object | `react to ...` changes the `direction` `Vector` in place, so it keeps its id |
| Macros that use `numpy` and `random` and call other macros with `@macro` | the `MACROS` block (geometry helpers, "fittest"-style scans) |
| Events raised by the state machine | `react to ...` raises `EVENT("public", "oh nooo there is an obstacle"/"...wall")` |
| Event definitions with payloads, one of them with a domain type | [EVENTS.kmeta](EVENTS.kmeta): `setSpeed` carries a `number`, `teleport` carries a `Vector` |

The macros use `numpy` and the standard modules `random` and `math`, so a run on your machine needs these Python
packages installed, see the requirements in [3.3 Running the runtime](../../docs/reference/3.3-running-the-runtime.md).
The Docker image has them.

## Lifecycle

```text
                            ┌────────────── react to obstacle ◀──┐ (obstacle closest & < 0.25)
                            │                                    │
ready ──(start)──▶ observe ─┼────────────── react to wall    ◀──┤ (wall closest & < 0.25)
                     ▲      │                                    │
                     │      └────────────────────────────────▶ drive ──▶ drive fast (both ≥ 1.0)
                     └──────────────────(fallback)──────────────┘   └──▶ drive slow (either < 1.0)
```

The robot stays in `ready` until it gets a `start` event. Each time `observe` is entered, the robot computes the
closest obstacle, the closest wall and their distances. Then it reacts if one of them is within `0.25` units, or goes
on to `drive`. In `drive` it takes `drive fast` in open space and `drive slow` near obstacles or walls, then goes back
to `observe`. `NOT LOOPING` keeps the transitions into the react and speed states from firing twice in a row, so the
transitions without a condition (`observe -> drive`, `drive -> observe`) always get a turn.

## Run it

```bash
ENV=$(curl -s -X POST http://localhost:8080/createEnvironment)

curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV"        -H "Content-Type: text/plain" --data-binary @cleaning_robot.kmeta
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV"            -H "Content-Type: text/plain" --data-binary @cleaning_robot.kmodel
curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=$ENV" -H "Content-Type: text/plain" --data-binary @EVENTS.kmeta
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV&attachedTo=turtle" -H "Content-Type: text/plain" --data-binary @cleaning_robot.kstates

curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV&milliseconds=300"

# Activate (required before start), then start the engine
curl -X POST "http://localhost:8080/runEnvironment?envKey=$ENV"
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV"
```

The state machine is attached to the robot object with the key `turtle`, defined in
[cleaning_robot.kmodel](cleaning_robot.kmodel). Then connect a WebSocket client, see
[3.5 WebSocket](../../docs/reference/3.5-websocket.md), and send the `start` event. `environmentKey` is the domain of
the event (`public`) and `messageType` its name:

```jsonc
{ "environmentKey": "public", "messageType": "start", "payload": "" }
```

An observer registered with `registerObjectObserver ... objectId=turtle` before `runEnvironment` sees the `log` of the robot change. The
objects inside the robot, such as its bounding box, need observers of their own. A listener registered with
`registerDomainListener ... domain=public`, also before `runEnvironment`, gets the events `oh nooo there is an obstacle` and
`oh nooo there is a wall` that the robot raises while it reacts.
