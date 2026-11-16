![Logo](https://avatars.githubusercontent.com/u/264260364?s=200&v=4)

# The karpfen Runtime

The karpfen runtime is a server that runs karpfen models. A client uploads a metamodel, a model, event definitions and
state machines over HTTP, runs them, sends events and watches the model change over a WebSocket.

## Documentation

| Section | Content |
|---|---|
| [3 Execution](docs/reference/3-execution.md) | What the runtime is and the life of an environment |
| [3.1 Execution semantics](docs/reference/3.1-execution-semantics.md) | Ticks, transitions, actions, events, splits and joins, histories, tick limits |
| [3.2 Runtime features](docs/reference/3.2-features.md) | Histories, tick limits, stepping, breakpoints, recording and replaying events |
| [3.3 Running the runtime](docs/reference/3.3-running-the-runtime.md) | Starting the runtime, `application.conf` and the trace log |
| [3.4 HTTP API](docs/reference/3.4-http-api.md) | An overview of the HTTP API. Every route with its answers and errors is in [docs/http-api.md](docs/http-api.md) |
| [3.5 WebSocket](docs/reference/3.5-websocket.md) | An overview of the WebSocket. Connecting, sending events and every pushed message are in [docs/websocket.md](docs/websocket.md) |
| [3.6 Examples](docs/reference/3.6-examples.md) | The example models in `examples` |
| [Developing the runtime](docs/development.md) | Structure, main classes, building and testing |

The table of all chapters of the karpfen reference is in the
[karpfen-IDE repository](https://github.com/karpfenproject/karpfen-IDE/blob/main/docs/reference/README.md).

## The language library

The runtime reads KMeta, KModel and KStates with the library
[karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools) in version 2.0. The library is the git
submodule `karpfen-dsl-tools`, so clone with `--recurse-submodules`. The languages are described in the reference of
the library:

- [2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md)
- [2.1 KMeta](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.1-kmeta.md)
- [2.2 KModel](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.2-kmodel.md)
- [2.3 KStates](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md)
- [2.4 Checks](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.4-checks.md)
- [2.5 Using the library](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.5-library.md)

## Demo

A simulation of a cyber-physical setting with several concurrent state machines and a world model is in the
[Turtle Robot Demo](https://github.com/karpfenproject/robot-model-js-client-demo).

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for details.

Parts of this project have been developed with the help of generative AI.
