# Autonomous Car — Parallel States (SPLIT / JOIN)

An autonomous car that runs a **pre-drive self-test of three subsystems concurrently**, then drives to a
goal once they all pass. This example showcases the parallel-states feature.

## Feature highlights

| Feature | Where to look |
|---------|---------------|
| `SPLIT` (1-to-N), event-guarded | `off -> checkBattery, checkBrakes, calibrateSensors` on `powerOn` |
| Branches advancing independently / at different rates | battery & sensors finish in one step; brakes take two (`checkBrakes -> bleedBrakes -> brakesReady`) |
| `JOIN` (N-to-1), fires only when *all* branches reach their join points | `batteryReady, brakesReady, sensorsReady -> ready` |
| Returning to simple (non-parallel) execution after the join | `ready -> driving -> parked` |

## Lifecycle

```
                                  ┌──────────▶ checkBattery ───────────────────▶ batteryReady ──┐
                                  │                                                              │
off ──powerOn──▶ SPLIT ──────────┼──────▶ checkBrakes ─▶ bleedBrakes ─────────▶ brakesReady ────┼──▶ JOIN ──▶ ready ──▶ driving ──(arrived)──▶ parked
                                  │                                                              │
                                  └──────────▶ calibrateSensors ───────────────▶ sensorsReady ──┘
```

Because the three branches tick independently, the battery and sensor checks reach their join points a
tick before the brake check does. The `JOIN` waits — it is "all regions have reached their join points",
not "some of them have" — and only collapses the branches back into a single `ready` state once the brake
branch catches up. From there the machine is simple again and drives to its goal.

### Why the `*Ready` states are empty

A `JOIN` fires as soon as every branch *sits on* its join-point state — which happens at the **end** of the
tick a branch transitions into that state, **before** the branch is ticked again. So a join-point state's
own `ENTRY` is not a reliable place to run actions (the join may grab the branch first). Each subsystem
therefore does its work and logs "self-test passed" in the step *before* its join point, and the
`batteryReady` / `brakesReady` / `sensorsReady` states are left as empty markers.

## Run it

```bash
ENV=$(curl -s -X POST http://localhost:8080/createEnvironment)

curl -X PUT "http://localhost:8080/setMetamodel?envKey=$ENV"        -H "Content-Type: text/plain" --data-binary @autonomous_car.kmeta
curl -X PUT "http://localhost:8080/setModel?envKey=$ENV"            -H "Content-Type: text/plain" --data-binary @autonomous_car.kmodel
curl -X PUT "http://localhost:8080/setEventDefinitions?envKey=$ENV" -H "Content-Type: text/plain" --data-binary @EVENTS.kmeta
curl -X PUT "http://localhost:8080/setStateMachine?envKey=$ENV&attachedTo=av-1" -H "Content-Type: text/plain" --data-binary @autonomous_car.kstates

curl -X POST "http://localhost:8080/setTickDelay?envKey=$ENV&milliseconds=300"
curl -X POST "http://localhost:8080/startEnvironment?envKey=$ENV"
```

Then connect a WebSocket client and send the ignition event (`environmentKey` is the event domain):

```jsonc
{ "environmentKey": "ignition", "messageType": "powerOn", "payload": "" }
```

Subscribe with `registerObjectObserver ... objectId=av-1` and watch the interleaved `[battery]`,
`[brakes]` and `[sensors]` log lines, the join into "All systems nominal", and then the drive to the goal.
