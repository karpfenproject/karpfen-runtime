# 3 Execution

The karpfen runtime is a server program that runs models. It reads a metamodel, a model, event definitions and state
machines in the languages of [2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md),
attaches each state machine to one object of the model and runs all of them in steps called ticks. A state machine
reads and changes its object and the objects it reaches from there. Events come from clients over a WebSocket and from
the state machines themselves. Clients watch the run over the same WebSocket.

The runtime holds everything in memory. One running runtime holds any number of environments. An environment is one
run of one model: it has its own metamodel, model, event definitions, state machines, settings, features, events and
connected clients. Environments do not see each other.

The IDE starts a runtime and drives it for you, as [4.5 Running](https://github.com/karpfenproject/karpfen-IDE/blob/main/docs/reference/4.5-running.md)
describes. Chapter 3 describes the runtime itself, for every client.

The life of an environment has these steps. Each step is one call of the HTTP API in
[3.4 HTTP API](3.4-http-api.md).

1. A client creates the environment and gets its key, for example `env-1763035112345`. The environment is in the
   phase `configuring`.
2. The client uploads the metamodel, then the model, the event definitions and one state machine per object that
   gets one. The runtime reads every upload at once and refuses a text with errors, line by line. An accepted upload
   replaces the one before it.
3. The client sets the tick delay and the time to live of events, registers the WebSocket clients that observe objects
   or listen to event domains, and switches features on.
4. The client runs the environment. From now on its uploads, settings and observers are fixed. The runtime checks that
   the environment has a metamodel and a model and that every state machine is attached to an object of the model.
   The environment is in the phase `activated`.
5. The client starts the environment. The state machines start in their initial states and tick until the environment
   is stopped. The phase is `running`. Events that clients send go to the state machines, and clients get the changes
   of the model and of the active states pushed over the WebSocket.
6. The client stops the environment. The model stays as the state machines left it. The phase is `stopped`.
7. The client may start the environment again. The state machines start again in their initial states, on the model
   as it is after the last run.

The phase `halted` means that the run ended on its own after an error inside the runtime, without a stop call. The
phase of an environment is part of every model snapshot of [3.4 HTTP API](3.4-http-api.md).

The sections of chapter 3:

| Section | What it covers |
|---|---|
| [3.1 Execution semantics](3.1-execution-semantics.md) | What happens in a tick: active states, transitions, actions, events, splits and joins, histories and tick limits |
| [3.2 Runtime features](3.2-features.md) | The features that change a run: histories, tick limits, stepping, breakpoints, recording and replaying events, branches on threads of their own |
| [3.3 Running the runtime](3.3-running-the-runtime.md) | Starting the runtime on your machine or in Docker, its configuration file and its trace log |
| [3.4 HTTP API](3.4-http-api.md) | Every route, its parameters, its answers and its errors, with one whole session |
| [3.5 WebSocket](3.5-websocket.md) | Connecting, sending events and every message the runtime pushes |
| [3.6 Examples](3.6-examples.md) | The example models that come with the runtime and how to run them |
