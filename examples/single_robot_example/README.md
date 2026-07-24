# Single Robot — End-to-End Walkthrough

A single cleaning robot ("turtle") that drives around a room, senses the closest obstacle and wall, and
steers away from whichever is too close. This is the original, full-featured example: it touches most of
the core DSL features in one coherent state machine, which makes it the best starting point before the
more focused examples.

## Feature highlights

| Feature | Where to look |
|---------|---------------|
| Hierarchical / nested states | `observe` contains `react to obstacle` / `react to wall`; `drive` contains `drive fast` / `drive slow` |
| `INITIAL` state + signal event to start | `ready` waits for `EVENT("public", "start")` before doing anything |
| `NOT LOOPING` transitions + unconditional fallbacks | `observe -> react to ...` and `drive -> drive fast/slow` are `NOT LOOPING`; `observe -> drive` and `drive -> observe` are the fallbacks |
| `EVAL` guards over model values | `drive -> drive slow/fast` branch on `$(d_closest_obstacle)` / `$(d_closest_wall)` |
| `SETOBJ` on a `knows` relation | `observe` repoints `closest_obstacle` / `closest_wall` to existing objects via macros |
| `SET` of a scalar from a `MACRO` | `observe` writes the `d_closest_*` distances |
| `WITH ... AS` value-wise update of an embedded (`has`) object | `react to ...` updates the `direction` `Vector` in place, preserving its id |
| `MACRO`s with `numpy` / `random` / nested `@macro` calls | the `MACROS` block (geometry helpers, "fittest"-style scans) |
| Publishing internal events | `react to ...` fires `EVENT("public", "oh nooo there is an obstacle"/"...wall")` |
| Event payload definitions (incl. an embedded domain type) | [EVENTS.kmeta](EVENTS.kmeta) — `setSpeed` carries a `number`, `teleport` carries a `Vector` |

> The macros use `numpy` (and the standard-library `random`/`math`), so a local run needs those Python
> packages installed — see the prerequisites in [`../../guides/getting_started.md`](../../guides/getting_started.md).
> The Docker image already bundles them.

## Lifecycle

```
                            ┌────────────── react to obstacle ◀──┐ (obstacle closest & < 0.25)
                            │                                    │
ready ──(start)──▶ observe ─┼────────────── react to wall    ◀──┤ (wall closest & < 0.25)
                     ▲      │                                    │
                     │      └────────────────────────────────▶ drive ──▶ drive fast (both ≥ 1.0)
                     └──────────────────(fallback)──────────────┘   └──▶ drive slow (either < 1.0)
```

The robot stays in `ready` until it receives a `start` event. From `observe` it refreshes its perception
(closest obstacle/wall and their distances) every time it is entered, then either reacts (if something is
within `0.25` units) or proceeds to `drive`. While driving it picks `drive fast` in the open or
`drive slow` near hazards, then loops back to `observe`. `NOT LOOPING` keeps the reactive and speed
transitions from firing twice in a row, so the unconditional fallbacks (`observe -> drive`,
`drive -> observe`) always get a turn.

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

The state machine is attached to the robot object whose id is `turtle` (defined in
[cleaning_robot.kmodel](cleaning_robot.kmodel)). Then connect a WebSocket client (see
[`../../guides/getting_started.md`](../../guides/getting_started.md)) and send the start signal —
`environmentKey` is the event **domain** (`public`), `messageType` is the event **name**:

```jsonc
{ "environmentKey": "public", "messageType": "start", "payload": "" }
```

Subscribe with `registerObjectObserver ... objectId=turtle` to watch the robot's `log` and
`boundingBox->position` evolve. You can also subscribe with `registerDomainListener ... domain=public`
to receive the `oh nooo there is an obstacle` / `oh nooo there is a wall` events the robot publishes while
reacting.
