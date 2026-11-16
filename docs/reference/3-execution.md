# 3 Execution

The karpfen runtime is a server program that runs models. It reads a metamodel, a model, event definitions and state
machines in the languages of
[2 Modeling languages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2-modeling-languages.md)
and runs the state machines on the objects of the model in steps called ticks. A state machine is either attached to
one object or attachable, in which case objects get instances of it at the start and while the model runs. It reads
and changes its object and every object it reaches from there. Events come from the state machines themselves and
from clients over a WebSocket, the same one over which clients watch the run.

A running runtime holds any number of environments, all of them in memory. An environment is one run of one model.
It does not see the other environments, because each one holds the following parts of its own.

- metamodel and model
- event definitions
- state machines
- settings
- features
- events
- connected clients

When you work in the IDE, it starts a runtime and drives it for you.
[4.5 Running](https://github.com/karpfenproject/karpfen-IDE/blob/main/docs/reference/4.5-running.md) describes this
from the side of the IDE. Chapter 3 is about the runtime itself and holds what every client needs to drive it.

An environment goes through the following steps in its life. Each step is one call of the
[3.4 HTTP API](3.4-http-api.md).

1. A client creates the environment and gets back its key, for example `env-1763035112345`. The new environment is in
   the phase `configuring`.
2. The client uploads the metamodel first and then the model, the event definitions, one attached state machine for
   each object that has one, the attachable state machines and the fragments that container states use. The runtime
   reads every upload as soon as it arrives. A text with errors is refused with an answer that names each error with
   its line. An accepted upload replaces the one before it.
3. The client sets the tick delay and the time to live of events and switches the result cache on or off. It
   registers the WebSocket clients that observe objects or listen to event domains and switches features on.
4. The client runs the environment. Before the runtime accepts the call, it checks that the environment has a
   metamodel and a model, that every attached state machine belongs to an object of the model, that every type that
   behaves after a state machine finds an attachable state machine that lists the type, and that every state machine
   flattens with its fragments. From then on the uploads, settings and observers are fixed. The environment is now in
   the phase `activated`.
5. When the client starts the environment, the phase becomes `running`. The state machines start in their initial
   states and tick until the environment is stopped. On the way, objects that rules create gain state machine
   instances and objects that rules remove lose theirs. Events that clients send go to the state machines. The runtime
   pushes the changes of the model and of the active states to the clients over the WebSocket.
6. When the client stops the environment, the phase becomes `stopped`. The model, the state machines with their active
   states and the waiting events stay as they are. The connections of the clients stay open.
7. The client may start the environment again. The run then continues with the next tick as if there had been no
   stop.
8. When the client no longer needs the environment, it drops it. That ends the engine, the Python processes and the
   connections of the environment and removes its key. A client may drop an environment in any phase. An environment
   that is never dropped stays in the runtime until the runtime ends, frozen if it was stopped.

The phase `halted` is not reached by any call. It means that the run ended on its own after an error inside the
runtime. The phase of an environment is part of every snapshot of the model that a client reads with the
[observation routes of the HTTP API](https://github.com/karpfenproject/karpfen-runtime/blob/main/docs/http-api.md#observation).

| Section | What it covers |
|---|---|
| [3.1 Execution semantics](3.1-execution-semantics.md) | What happens in a tick: active states, transitions, actions, events, splits and joins, histories, tick limits and the life of state machine instances |
| [3.2 Runtime features](3.2-features.md) | The features that change a run: histories, tick limits, stepping, breakpoints, recording and replaying events, branches on threads of their own |
| [3.3 Running the runtime](3.3-running-the-runtime.md) | Starting the runtime on your machine or in Docker, its configuration file and its trace log |
| [3.4 HTTP API](3.4-http-api.md) | What the HTTP API offers someone who writes a client of the runtime, in brief |
| [3.5 WebSocket](3.5-websocket.md) | What the WebSocket offers someone who writes a client of the runtime, in brief |
| [3.6 Examples](3.6-examples.md) | The example models that come with the runtime and how to run them |
