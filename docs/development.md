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
| `EngineTraceLogger`, `PathNumbers` | The trace log. `PathNumbers`, owned by the `StateMachineInstanceFactory` of an engine, gives every `Branch` its path number when `SMContext.newBranch` builds it; the trace entries and the identity under which the branch consumes events both read it from the branch. `StateMachineLifecycle` tells its listener which instance an exchange replaced, so the engine writes one `STATE_MACHINE_EXCHANGED` entry |
| `FeatureManager`, `FeatureRegistry` | Features, found as `FeatureProvider` services |
| `ObservationRelay`, `ModelSnapshotService`, `ClientSessionManager`, `WebSocketBroadcaster` | Revisions, snapshots and the messages to clients; `StateMachineRecord` and `ActiveStackRecord` keep the instances and stacks for snapshots |

A stop freezes an environment: `EnvironmentThread.stop` ends the event loop and calls `Engine.stop`, which lets the
running tick end and holds a thread that waits for TickByTick where it waits; `EventBus.freeze` stops the time to live
of the events. `Engine.start` continues, `Engine.close` and `EnvironmentThread.dispose` end an engine for good, and
`EnvironmentHandler.shutdown` does that for every environment when the runtime ends.

`EnvironmentHandler.dropEnv` drops one environment. Under its registry lock, which creating, activating and
registering clients take as well, the key leaves the three maps and `ClientSessionManager.forgetEnvironment` removes the
access keys, sessions, subscriptions and queued messages of the environment. Outside the lock `EnvironmentThread.dispose`
closes the engine; `Engine.close` makes every later wait for a pause return at once, so a breakpoint reached after its
resume cannot hold the end, and aborts the Python sessions through `PythonSessionPool.abort` when the tick does not end
within 5 s. Then the sockets close with `EnvironmentHandler.DROPPED`. Keys come from a counter that only grows, so a
dropped key never returns.

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
