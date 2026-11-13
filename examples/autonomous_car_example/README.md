# Autonomous car: parallel states with SPLIT and JOIN

An autonomous car tests three subsystems in parallel branches before it drives, and drives to a goal once all three
tests pass.

## What it shows

| Feature | Where to look |
|---------|---------------|
| `SPLIT` from one state into three, on an event | `off -> checkBattery, checkBrakes, calibrateSensors` on `powerOn` |
| Branches that advance on their own, at different rates | battery and sensors finish in one step; brakes take two (`checkBrakes -> bleedBrakes -> brakesReady`) |
| `JOIN` from three states into one, which fires only when all branches are in their join points | `batteryReady, brakesReady, sensorsReady -> ready` |
| Running without branches after the join | `ready -> driving -> parked` |

## Lifecycle

```text
                                  ┌──────────▶ checkBattery ───────────────────▶ batteryReady ──┐
                                  │                                                              │
off ──powerOn──▶ SPLIT ──────────┼──────▶ checkBrakes ─▶ bleedBrakes ─────────▶ brakesReady ────┼──▶ JOIN ──▶ ready ──▶ driving ──(arrived)──▶ parked
                                  │                                                              │
                                  └──────────▶ calibrateSensors ───────────────▶ sensorsReady ──┘
```

Each branch takes its own steps, so the battery and sensor checks reach their join points a tick before the brake
check. The `JOIN` waits until all branches are in their join points, and only then leads them into the one state
`ready`. From there the state machine has no branches and drives to its goal.

### Why the `*Ready` states are empty

A `JOIN` fires as soon as every branch is in its join-point state. A branch is there from the end of the tick in
which it takes the transition into that state, before it does its next step. The `ENTRY` block of a join-point state
may therefore not run before the join takes the branch. Each subsystem does its work and logs "self-test passed" in
the state before its join point, and the states `batteryReady`, `brakesReady` and `sensorsReady` are empty.

## Run it

```bash
ENV=$(curl -s -X POST http://localhost:8080/createEnvironment)

curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV"        -H "Content-Type: text/plain" --data-binary @autonomous_car.kmeta
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV"            -H "Content-Type: text/plain" --data-binary @autonomous_car.kmodel
curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=$ENV" -H "Content-Type: text/plain" --data-binary @EVENTS.kmeta
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV&attachedTo=av-1" -H "Content-Type: text/plain" --data-binary @autonomous_car.kstates

curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV&milliseconds=300"

# Activate (required before start), then start the engine
curl -X POST "http://localhost:8080/runEnvironment?envKey=$ENV"
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV"
```

Then connect a WebSocket client and send the event `powerOn`. `environmentKey` is the domain of the event:

```jsonc
{ "environmentKey": "ignition", "messageType": "powerOn", "payload": "" }
```

An observer registered with `registerObjectObserver ... objectId=av-1` before `runEnvironment` sees the log lines of `[battery]`, `[brakes]`
and `[sensors]` in turn, then "All systems nominal" after the join, and then the drive to the goal.
