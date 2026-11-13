![Logo](https://avatars.githubusercontent.com/u/264260364?s=200&v=4)

# The karpfen Runtime

Lightweight runtime server for Karpfen models with an HTTP API and WebSocket support.

## Runtime Guides

- [Documentation Index](guides/index.md)
- [Getting Started](guides/getting_started.md)
- [HTTP API Endpoints](guides/http_api_endpoints.md)
- [Quick Reference](guides/quick_reference.md)
- [State Machine Execution Semantics](guides/statemachine_execution_semantics.md)
- [Examples](examples/README.md)

## The Language Library

The runtime reads KMeta, KModel and KStates with the library
[karpfen-dsl-tools](https://github.com/karpfenproject/karpfen-dsl-tools) in version 2.0, its packages below
`io.karpfen.dsl`. The library is the git submodule `karpfen-dsl-tools/`, so clone with `--recurse-submodules`, and a
Gradle subproject of the runtime's build, which generates its parsers from the grammars. Its comparison with version
1.0, which needs the library's own git history, is not part of the runtime's build or of the Docker image.

The upload routes read every text with the library's readers. A text with errors is rejected with `400` and a body
that lists each error with its line, column, message and code; see
[Rejected uploads](guides/http_api_endpoints.md#rejected-uploads).

## DSL Tool Guides

- [KMeta Grammar Guide](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/guides/kmeta_grammar_guide.md)
- [KModel Grammar Guide](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/guides/kmodel_grammar_guide.md)
- [KStates Grammar Guide](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/guides/kstates_grammar_guide.md)
- [Verification Guide](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/guides/verification.md), with every code of an error or warning

## ⭐ Demos ⭐

You find a demo for a simulation of a cyber-physical setting using multiple concurrent statecharts and a world model here [Turtle Robot Demo](https://github.com/karpfenproject/robot-model-js-client-demo)

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for details.

---

*Parts of this project have been developed with the help of generative AI*.