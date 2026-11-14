# Developing the runtime

This page is for people who work on the runtime itself. The reference for users is in
[reference/README.md](reference/README.md).

## Structure

| Folder | Gradle project | Content |
|---|---|---|
| `src/main/kotlin` | `karpfen-runtime` | The server: `Main.kt`, the HTTP routes and the WebSocket (`server`), environments and their threads (`env`), snapshots and pushes of the model (`observation`), WebSocket sessions (`websocket`) and `application.conf` (`config`) |
| `execution-engine` | `execution-engine` | The engine: `Engine.kt` with the tick loop, the steps of state machines, actions, conditions, events, `EVAL` and macros (`exec`), the event bus (`messages`) and the features (`features`) |
| `karpfen-dsl-tools` | `karpfen-dsl-tools` | The git submodule with the grammars and readers of KMeta, KModel and KStates, version 2.0 |
| `examples` | | The example models of [3.6 Examples](reference/3.6-examples.md) |

## Main classes

| Class | Role |
|---|---|
| `Main.kt` | Reads `application.conf`, starts the server and the WebSocket broadcaster |
| `KtorServer`, `HTTPRoutes`, `APIService` | The HTTP routes and the WebSocket endpoint, and the calls behind them |
| `EnvironmentHandler`, `Environment`, `EnvironmentThread` | The environments, their configuration, activation and the thread that runs each |
| `Engine`, `BranchRunner`, `TransitionProcessor`, `ActionProcessor` | The tick loop and what happens in a tick |
| `StateMachineLifecycle`, `StateMachineDefinitions`, `StateMachineInstanceFactory` | The state machine instances of an engine: the attached and attachable state machines, the instances at a start, their order, and the instances that join and leave at the end of each tick. `ModelQueryProcessor` tells the lifecycle about added and removed objects through `ModelStructureListener`, and `ActionProcessor` hands it `ATTACH` and `EXCHANGE` through `StateMachineAttachments` |
| `MacroProcessor`, `MacroCodeTransformer`, `ExpressionClassifier` | `EVAL` and macros, with or without Python |
| `ParallelBranchStepper`, `TickGate`, `PythonSessionPool` | The threads of the branches while TrueParallel is on, the gate between ticks and readers, and one Python session per call that runs at the same time |
| `EventBus`, `EventProcessor` | Events, their time to live and their consumption per branch |
| `FeatureManager`, `FeatureRegistry` | Features, found as `FeatureProvider` services |
| `ObservationRelay`, `ModelSnapshotService`, `ClientSessionManager`, `WebSocketBroadcaster` | Revisions, snapshots and the messages to clients; `StateMachineRecord` and `ActiveStackRecord` keep the instances and stacks for snapshots |

A stop freezes an environment: `EnvironmentThread.stop` ends the event loop and calls `Engine.stop`, which lets the
running tick end and holds a thread that waits for TickByTick where it waits; `EventBus.freeze` stops the time to live
of the events. `Engine.start` continues, `Engine.close` and `EnvironmentThread.dispose` end an engine for good, and
`EnvironmentHandler.shutdown` does that for every environment when the runtime ends.

## Startup

`Main.kt` reads `application.conf` from the working directory, hands the engine defaults to `EnvironmentHandler`,
starts the `WebSocketBroadcaster` when `websocket.enabled` is true, and starts the Ktor server on `server.host` and
`server.port`. A shutdown hook stops the server.

## Building and testing

The build needs JDK 21. Clone with `--recurse-submodules`. The build generates the parsers of `karpfen-dsl-tools` from
its grammars and changes files in the submodule; `git -C karpfen-dsl-tools checkout -- .` restores them.

```bash
./gradlew installDist -x test
./gradlew test
./gradlew test -PskipPythonTests
```

Tests that run Python carry the JUnit tag `python`. `-PskipPythonTests` leaves them out, in both Gradle projects.
