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
| `MacroProcessor`, `MacroCodeTransformer`, `ExpressionClassifier` | `EVAL` and macros, with or without Python |
| `EventBus`, `EventProcessor` | Events, their time to live and their consumption per branch |
| `FeatureManager`, `FeatureRegistry` | Features, found as `FeatureProvider` services |
| `ObservationRelay`, `ModelSnapshotService`, `ClientSessionManager`, `WebSocketBroadcaster` | Revisions, snapshots and the messages to clients |

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
