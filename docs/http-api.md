# The HTTP API of the runtime

This document is the complete description of the HTTP API for people who write a client of the runtime. A client
drives the runtime over HTTP: with the routes of this document it creates environments, uploads models, runs them,
registers WebSocket clients and talks to features. The IDE does all of this on its own, so a user of the IDE does not
need the document. A short overview is in [3.4 HTTP API](reference/3.4-http-api.md), and the messages that the
runtime pushes over the WebSocket are described in [The WebSocket of the runtime](websocket.md).

All examples talk to a runtime at `http://127.0.0.1:8080`. Most of them use the lamp example project of the IDE in the
folder `examples/lamp`. Where an example needs something that the lamp example project does not have, such as an
attachable state machine or a fragment, the text says so.

## Conventions

Parameters go in the query string. Uploads and messages to features go in the body as plain text. The runtime accepts
requests from any origin.

A successful call answers with status 200 and an empty body, unless the description of the route names an answer. A
failed call answers with one of the following statuses and gives the reason as plain text in the body.

| Status | When |
|---|---|
| 400 | A parameter is missing or wrong, the environment does not exist, a name is unknown, or an upload has errors |
| 409 | The call does not fit the phase of the environment or what it holds |
| 500 | Anything else went wrong |

A missing parameter gives the text `Missing required parameter: <name>`. An unknown environment gives
`Environment with key <envKey> does not exist`.

| Group | Routes |
|---|---|
| [Environments](#environments) | `POST /createEnvironment`, `POST /dropEnvironment` |
| [Uploads](#uploads) | `PUT /setMetamodel`, `PUT /setModel`, `PUT /setEventDefinitions`, `PUT /setStateMachine` |
| [Settings](#settings) | `POST /setTickDelay`, `POST /setEventTtl`, `POST /setResultCaching` |
| [Clients](#clients) | `POST /registerClientForWebSocket`, `POST /registerObjectObserver`, `POST /registerDomainListener` |
| [Running](#running) | `POST /runEnvironment`, `POST /startEnvironment`, `POST /stopEnvironment`, `POST /setActiveState` |
| [Observation](#observation) | `GET /observatory/environments`, `GET /observatory/statemachine`, `GET /observatory/stateMachines`, `POST /observatory/registerClient`, `GET /observatory/model`, `POST /observatory/subscribeModel`, `POST /observatory/unsubscribeModel`, `GET /observatory/metrics` |
| [Health](#health) | `GET /health` |
| [Features](#features) | `GET /getRegisteredFeatures`, `GET /getActiveFeatures`, `POST /activateFeature`, `POST /deactivateFeature`, `POST /sendMessageToFeature` |

## Environments

`POST /createEnvironment` creates an environment in the phase `configuring` and answers with its key as text. The key
has the form `env-<milliseconds since 1970>`. One runtime never hands out the same key twice.
This call creates an environment and gets its key back.

```bash
curl -X POST "http://127.0.0.1:8080/createEnvironment"
```

```text
env-1763035112345
```

`POST /dropEnvironment` with `envKey` drops the environment for good. The route works in every phase, from
`configuring` through `activated`, `running` and `halted` to `stopped`. A drop ends everything the environment owns.

- the engine with its thread and the threads of branches
- a pause of TickByTick or a halt at a breakpoint
- the Python sessions
- the features with what they recorded
- the events that wait
- the observers and listeners
- the access keys that no client has used yet
- the WebSocket connections of its clients, which close with code 1000 and the reason `Environment dropped`

The trace log is closed and stays on disk. Pushes that the runtime has not sent yet when the drop comes are not sent
any more. A client that needs the last state of the model reads the snapshot of [Observation](#observation)
before it drops the environment.

The call answers with status 200 and an empty body once the engine has ended. Normally that is within the rest of
the running tick. If the tick does not end within 5 s, for example because an `EVAL` waits for Python, the runtime
ends the Python processes of the environment and interrupts its thread. The call answers after that. A drop that
comes while a stop or a start is under way waits for it to finish.

From the moment the drop begins, every route answers for the key as for an environment that never existed. The key no
longer appears in `observatory/environments`. The runtime never hands it out again. When two drops of one environment
come at the same time, one of them answers 200. The other answers 400 with the same text as for a key that never
existed, `Environment with key <envKey> does not exist`.

This call drops the environment.

```bash
curl -X POST "http://127.0.0.1:8080/dropEnvironment?envKey=env-1763035112345"
```

## Uploads

The four upload routes take the text of a file as the body and the key of the environment as `envKey`. They work only
in the phase `configuring`. After `runEnvironment` they answer 409 with
`Environment with key <envKey> is already active and cannot be modified in the intended way.`

The runtime reads every upload as soon as it arrives. A text with errors leaves the environment as it was and
is refused with the answer of [Rejected uploads](#rejected-uploads). An accepted upload replaces the one
before it. A byte order mark at the very start of a text is ignored. Warnings of the readers do not refuse an upload.
The runtime prints them on its console.

| Route | Parameters | Body |
|---|---|---|
| `PUT /setMetamodel` | `envKey` | A KMeta text |
| `PUT /setModel` | `envKey` | A KModel text |
| `PUT /setEventDefinitions` | `envKey` | A KMeta text with the event definitions |
| `PUT /setStateMachine` | `envKey`, `attachedTo` for an attached state machine | A KStates text, also a fragment |

`setModel` reads the model against the metamodel of the environment. Without a metamodel it answers 409 with
`Metamodel must be set before receiving a model for environment <envKey>`. A metamodel uploaded after the model does
not make the runtime read the model again.

The event definitions are
[2.1 KMeta](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.1-kmeta.md) types, one per
event and named like the event. Because `setEventDefinitions` reads them with the types of the metamodel that the
environment holds at that moment, an event type can use the types of the domain if the metamodel was uploaded first.

`setStateMachine` takes a state machine in one of the two forms of
[2.3.11 State machine management and lifecycle](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2311-state-machine-management-and-lifecycle),
or a fragment. With `attachedTo`, the text must be an attached state machine, `STATEMACHINE ATTACHED TO "Type"`. The
runtime attaches it to the object of the model whose key is `attachedTo`. An object has at most one attached state
machine. A second upload for the same key replaces the first and keeps its place in the order of the state machines.
The runtime does not compare the type in `ATTACHED TO` with the type of the object.

Without `attachedTo`, the text must be an attachable state machine, `STATEMACHINE "name" ATTACHABLE TO "Type", ...`,
or a fragment. The runtime keeps an attachable state machine as a definition under its name. Objects get
instances of it at run time by the rules of
[3.1.13 State machine management and lifecycle](reference/3.1-execution-semantics.md#3113-state-machine-management-and-lifecycle).
A second upload with the same name replaces it and keeps its place. An environment holds any number of definitions
besides its attached state machines.

A fragment, `STATEMACHINE "name" FRAGMENT` in the form of
[2.3.12 Pages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2312-pages),
is kept under its name and is never attached to an object. Container states of other state machines and fragments
use it. How the runtime runs them is described in
[3.1.14 Pages](reference/3.1-execution-semantics.md#3114-pages). Because `runEnvironment` flattens the state
machines with their fragments, every fragment that a state machine of the environment uses, directly or through
other fragments, has to be uploaded before `runEnvironment`.

Attachable state machines and fragments share one name space. An upload replaces the one of the same name, whichever
form it had. If both have the same form, the new one keeps the place of the old one. If the forms differ,
the old one leaves and the new one comes at the end of its kind. `observatory/statemachine` answers with the text of a
fragment by its name, but `observatory/stateMachines` does not list fragments. An empty name in the header of a
fragment or an attachable state machine, such as `STATEMACHINE "" FRAGMENT`, is an error of the reader. So is an empty
name after `USE`. Such a text is refused like any other text with errors.

The runtime reads a KStates text with all optional features of the language switched on. A text in the wrong form for
the call leaves the environment as it was and is refused with status 400 and one of these texts.

- `The state machine for '<attachedTo>' must have the attached form STATEMACHINE ATTACHED TO "Type", but it is the attachable state machine '<name>'; upload an attachable state machine without attachedTo`
- `The state machine for '<attachedTo>' must have the attached form STATEMACHINE ATTACHED TO "Type", but it is the fragment '<name>'; upload a fragment without attachedTo`
- `A state machine without attachedTo must have the attachable form STATEMACHINE "name" ATTACHABLE TO "Type" or the fragment form STATEMACHINE "name" FRAGMENT; give attachedTo for a state machine ATTACHED TO a type`

The first five of these calls upload the lamp example project. The last two show the other two forms. `glowing` is an
attachable state machine like the one of the spares example project. `bulb test` is the fragment of the example in
[2.3.12 Pages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2312-pages).

```bash
curl -X PUT "http://127.0.0.1:8080/setMetamodel?envKey=env-1763035112345" --data-binary @lamp.kmeta
curl -X PUT "http://127.0.0.1:8080/setModel?envKey=env-1763035112345" --data-binary @lamp.kmodel
curl -X PUT "http://127.0.0.1:8080/setEventDefinitions?envKey=env-1763035112345" --data-binary @lamp-events.kmeta
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=env-1763035112345&attachedTo=lamp" --data-binary @lamp.kstates
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=env-1763035112345&attachedTo=wallSwitch" --data-binary @switch.kstates
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=env-1763035112345" --data-binary @glowing.kstates
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=env-1763035112345" --data-binary "@bulb test.frag.kstates"
```

Send the files with `--data-binary`. The option `-d` drops the line breaks, after which the line numbers of errors no
longer fit the file.

## Rejected uploads

An upload with errors answers with status 400. The first line of the body names the upload and the number of errors.
Each further line holds one error with its line, its column, its message and its code. The lines are separated by
line feeds, with none at the end.

```text
Upload of the <upload> rejected: <n> errors
<line>:<column>: <message> (<code>)
```

| Upload | `<upload>` |
|---|---|
| `setMetamodel` | `metamodel` |
| `setModel` | `model` |
| `setEventDefinitions` | `event definitions` |
| `setStateMachine` with `attachedTo` | `state machine for '<attachedTo>'` |
| `setStateMachine` without `attachedTo`, a text that reads as a fragment | `fragment` |
| `setStateMachine` without `attachedTo`, any other text | `attachable state machine` |

With one error the first line ends in `1 error`. The codes and messages are those of the checks in
[2.4 Checks](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.4-checks.md).

A metamodel with an unknown property type and a relation to a missing type is refused with two errors.

```kmeta
type "Bulb" "A light bulb" {
    prop("watts", "decimal")
}
type "Lamp" "A lamp" {
    has("bulb", "Bulb")
    knows("control", "Switch")
}
```

```text
Upload of the metamodel rejected: 2 errors
2:20: Unknown type 'decimal' of prop 'watts'; a prop is of type 'string', 'number', 'boolean' (kmeta.unknown-property-type)
6:23: Unknown class type 'Switch' of knows 'control' in type 'Lamp' (kmeta.unknown-target-type)
```

A model with a wrong number and an unknown property, uploaded against the metamodel of the lamp example project, gets
this answer.

```kmodel
make object "lamp":"Lamp" {
    prop("mode") -> "off"
    has("bulb") -> make object "bulb":"Bulb" {
        prop("watts") -> "bright"
        prop("colour") -> "white"
    }
}
```

```text
Upload of the model rejected: 2 errors
4:27: 'bright' is not a number, but prop 'watts' is of type 'number' (kmodel.invalid-number)
5:15: Class type 'Bulb' has no property 'colour' (object 'bulb') (kmodel.unknown-property)
```

For an attached state machine with a transition to a state that does not exist, the first line of the answer names
the object the state machine was uploaded for.

```kstates
STATEMACHINE ATTACHED TO "Lamp" {
    STATES {
        INITIAL STATE "off" { }
        STATE "on" { }
    }
    TRANSITIONS {
        TRANSITION "off" -> "on" {
            CONDITION { EVENT("switch", "press") }
        }
        TRANSITION "on" -> "dark" {
            CONDITION { EVENT("switch", "press") }
        }
    }
}
```

```text
Upload of the state machine for 'lamp' rejected: 1 error
10:29: No state is named 'dark'; check the spelling or define the state (kstates.unknown-state)
```

## Settings

The three settings can be changed only in the phase `configuring`. After it they answer 409 with the same text as the
uploads.

| Route | Parameters | What it sets |
|---|---|---|
| `POST /setTickDelay` | `envKey`, `milliseconds` | The tick delay, the least time from the start of one tick to the start of the next |
| `POST /setEventTtl` | `envKey`, `ttlMs` | The time to live of events in milliseconds. 0 means that events never expire |
| `POST /setResultCaching` | `envKey`, `enabled` | With `true` or `false`, whether the environment answers code that it ran in Python before from the result cache of [3.1.6.6 The result cache](reference/3.1-execution-semantics.md#3166-the-result-cache) |

A wrong value is refused with status 400. For `milliseconds` and `ttlMs` that is a value that is no whole number. The
text is then `Missing or invalid required parameter: milliseconds` or `Missing or invalid required parameter: ttlMs`.
For `enabled` it is anything other than `true` or `false`, with the text
`Missing or invalid required parameter: enabled`. An environment without these calls takes its values from
`engine.defaultTickDelayMs`, `engine.defaultEventTtlMs` and `engine.resultCaching` of
[3.3.4 Configuration](reference/3.3-running-the-runtime.md#334-configuration).

These calls set a tick delay of 500 ms, a time to live of 5 s for events, and no result cache.

```bash
curl -X POST "http://127.0.0.1:8080/setTickDelay?envKey=env-1763035112345&milliseconds=500"
curl -X POST "http://127.0.0.1:8080/setEventTtl?envKey=env-1763035112345&ttlMs=5000"
curl -X POST "http://127.0.0.1:8080/setResultCaching?envKey=env-1763035112345&enabled=false"
```

## Clients

`POST /registerClientForWebSocket` with `clientId` and `envKey` registers a client for the WebSocket of the
environment. The client chooses its id itself. The route works in every phase and answers with an access key as
text, in the form `ak-<clientId>-<milliseconds>-<number>`. The client sends the access key when it connects, in the
first frame that [Connecting](websocket.md#connecting) describes. An access key works for one
connection only.

`POST /registerObjectObserver` with `envKey`, `clientId` and `objectId` makes the client get the message
`objectChanged` when the object with this key changes and `objectDeleted` when it is removed.
`POST /registerDomainListener` with `envKey`, `clientId` and `domain` makes the client get the message `domainEvent`
for every event of this domain. Both routes work only in the phase `configuring` and answer 409 after it.

A client `panel` registers, observes the bulb and listens to the domain `switch`. Only the first of the three calls
answers with a body.

```bash
curl -X POST "http://127.0.0.1:8080/registerClientForWebSocket?clientId=panel&envKey=env-1763035112345"
curl -X POST "http://127.0.0.1:8080/registerObjectObserver?envKey=env-1763035112345&clientId=panel&objectId=bulb"
curl -X POST "http://127.0.0.1:8080/registerDomainListener?envKey=env-1763035112345&clientId=panel&domain=switch"
```

```text
ak-panel-1763035113001-4821
```

## Running

`POST /runEnvironment` with `envKey` fixes the configuration and makes the environment ready to start. The environment
is then in the phase `activated`. The call is refused with status 409 in these cases.

| Case | Text |
|---|---|
| The environment lacks the metamodel or the model | `Environment with key <envKey> must have both a metamodel and a model before it can be run` |
| A state machine is attached to a key that no object of the model has | `Environment with key <envKey> has state machines attached to objects missing from its model: <keys>`, with the keys separated by `, ` |
| A type of the metamodel behaves after a name that no attachable state machine of the environment has | `Environment with key <envKey> has types that behave after state machines it does not hold: <list>`. Each entry of the list reads `'Bulb' behaves after 'glowing'`, and the entries are separated by `, ` |
| The attachable state machine does not list the type | `Environment with key <envKey> has types that behave after state machines not attachable to them: <list>`. Each entry reads `'Bulb' behaves after 'glowing', which is attachable to 'Lamp'`, and the entries are separated by `; ` |
| [3.1.14.1 Flattening at activation](reference/3.1-execution-semantics.md#31141-flattening-at-activation) fails for a state machine with the fragments of the environment | `Environment with key <envKey> has state machines that cannot be flattened:` followed by one line per problem |
| The environment ran before | `Environment with key <envKey> is already active and cannot be modified in the intended way.` |

Each line of a failed flattening starts with the state machine, `State machine for '<attachedTo>'` or
`Attachable state machine '<name>'`, followed by a colon and the problem in the words of
[2.3.12 Pages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2312-pages).
The lines are separated by line feeds, with none at the end. This is the answer for a lamp whose container state uses
a fragment that was not uploaded.

```text
Environment with key env-1763035112345 has state machines that cannot be flattened:
State machine for 'lamp': Container state 'self test' of the state machine attached to 'Lamp' uses 'bulb test', but no fragment is named 'bulb test'
```

The flattening fails on any of the following problems.

- a container state that uses a name that no fragment has, or the name of an attachable state machine
- a fragment that contains itself
- a qualified name that equals the name of another state
- two different macros of one name
- a macro call that no file of the composition defines
- a macro call whose arguments do not fit the macro that another file of the composition defines

The last problem reads like this for a lamp that calls the macro `half` of its fragment with two arguments.

```text
State machine for 'lamp': In the state machine attached to 'Lamp': Macro 'half' takes 1 argument, but the call passes 2; pass one argument per TAKES
```

After a refusal for one of these problems, or for any other case of the table except the last, the environment stays
configurable. The client can upload what is missing and call `runEnvironment` again.

An environment without state machines can be run as well. It then only ticks.

`POST /startEnvironment` with `envKey` starts the ticks and puts the environment in the phase `running`. Starting a
running environment changes nothing and answers 200. Starting a stopped environment continues the frozen run with the
next tick and tells every connected client through `engineStatus` with the phase `running`. The continued run is
described in
[3.1.13.9 Stop and start, forcing and clients](reference/3.1-execution-semantics.md#31139-stop-and-start-forcing-and-clients).

`POST /stopEnvironment` with `envKey` freezes the environment and puts it in the phase `stopped`. The runtime waits up
to 5 s for the running tick to end, keeps everything for the next start, and sends every connected client
`engineStatus` with the phase `stopped`.

While the environment is stopped, its WebSocket connections stay open and the snapshot and the other routes of
[Observation](#observation) answer. Events that clients send wait for the start. Uploads and settings stay
refused as after `runEnvironment`. Stopping a stopped environment, or one that was never started, changes nothing and
answers 200.

A stopped environment stays frozen until it is dropped or the runtime ends. A drop closes its WebSocket connections
with code 1000 and the reason `Environment dropped`, the end of the runtime with code 1001 and the reason
`Runtime stopped`.

Before `runEnvironment`, `startEnvironment` and `stopEnvironment` answer 409 with
`Environment with key '<envKey>' has no execution thread`.

`POST /setActiveState` with `envKey`, `modelElement` and `state` forces the state machine instance of the object
`modelElement` into the state `state`. This works also for an instance that joined during the run. What forcing does
to the run is explained in [3.1.11 Forcing a state](reference/3.1-execution-semantics.md#3111-forcing-a-state).

Before `runEnvironment` the call answers 409 with `Environment with key <envKey> is not active`. An object without a
state machine instance gives 400 with `No state machine for model element '<modelElement>' in environment <envKey>`.
A state that the instance does not have gives 400 with `Unknown state '<state>' for model element '<modelElement>'`.
While the environment is stopped, the forced state takes effect at the first tick after the start. The force removes
no event. The events on the bus when it takes effect count as handled by the forced state machine and by no other.

A state inside a container state is named with its qualified name, such as `self test/lit`. Forcing a container
state goes down into its fragment. Qualified names are explained in
[3.1.14.2 Qualified names](reference/3.1-execution-semantics.md#31142-qualified-names).

These four calls activate the environment of the lamp example project with `runEnvironment`, start it, force the lamp
into `dimmer` and stop it.

```bash
curl -X POST "http://127.0.0.1:8080/runEnvironment?envKey=env-1763035112345"
curl -X POST "http://127.0.0.1:8080/startEnvironment?envKey=env-1763035112345"
curl -X POST "http://127.0.0.1:8080/setActiveState?envKey=env-1763035112345&modelElement=lamp&state=dimmer"
curl -X POST "http://127.0.0.1:8080/stopEnvironment?envKey=env-1763035112345"
```

## Observation

`GET /observatory/environments` lists every environment that was run, also the stopped ones. For each it gives the
keys of the objects that have state machine instances, in the order in which the instances step. Before the first
start these are the instances that the start will build. After it they are those of the latest tick. The answer
is a JSON array with one object per environment, in which `envKey` holds the key of the environment and
`modelElements` the keys of the objects. For the lamp example project it reads as follows.

```json
[{"envKey":"env-1763035112345","modelElements":["lamp","wallSwitch"]}]
```

`GET /observatory/statemachine` with `envKey` and `modelElement` answers with the KStates text of the state machine of
the object. If the instance on the object runs an attachable state machine, the answer is the text of that attachable
state machine. Otherwise it is the text uploaded with `attachedTo` for the object. An object with neither gives 400 with
`No state machine source found for model element '<modelElement>' in environment <envKey>`.

With `name` in place of `modelElement` the route answers with the text of the attachable state machine or the fragment
of that name, or with 400 and `No attachable state machine named '<name>' in environment <envKey>`. Without both
parameters it answers 400 with `Missing required parameter: modelElement`.

`GET /observatory/stateMachines` with `envKey` answers with what the environment holds of state machines, as JSON with
the content type `application/json`. The route works in every phase.

| Entry | Meaning |
|---|---|
| `attached` | The keys of the attached state machines, in upload order |
| `definitions` | The attachable state machines in upload order, each with its `name` and the list `types` of the types it is attachable to |
| `instances` | The instances in the order in which they step, each with the key of its object in `modelElement` and the name of its definition in `stateMachine`, `null` for an attached state machine. Empty before `runEnvironment` |

For an environment with an attached state machine on `living room` and the attachable state machines `glowing` and
`blinking`, the answer reads as follows.

```json
{"attached":["living room"],"definitions":[{"name":"glowing","types":["Bulb"]},{"name":"blinking","types":["Bulb"]}],"instances":[{"modelElement":"living room","stateMachine":null},{"modelElement":"bulb","stateMachine":"glowing"}]}
```

`POST /observatory/registerClient` with `clientId`, `envKey` and `modelElement` registers a client for the WebSocket in
the same way as `registerClientForWebSocket` and answers with an access key. The client also gets the message
`stateChange` of the state machine on the object `modelElement`, or of all state machines when `modelElement` is `*`.
The route works in every phase.

`GET /observatory/model` with `envKey` answers with the whole model of the environment as JSON, with the content type
`application/json; charset=UTF-8`. This snapshot was taken in the phase `configuring`.

```json
{"environmentKey":"env-1763035112345","phase":"configuring","revision":0,"activeStates":{},"stateMachines":{},"roots":[{"__id__": "living room", "__type__": "Room", "lamp": {"__id__": "lamp", "__type__": "Lamp", "mode": "off", "bulb": {"__id__": "bulb", "__type__": "Bulb", "maxWatts": 40, "watts": 0, "lit": false}, "spare": {"__id__": "spare", "__type__": "Bulb", "maxWatts": 60, "watts": 0, "lit": false}, "control": {"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}}, "switch": {"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}}]}
```

| Entry | Meaning |
|---|---|
| `environmentKey` | The key of the environment |
| `phase` | `configuring`, `activated`, `running`, `halted` or `stopped`, the phases of [3 Execution](reference/3-execution.md) |
| `revision` | The revision of the last change the snapshot contains, 0 before the first change |
| `activeStates` | For each object with a state machine instance, the last reported stack, with the names of [3.1.14.2 Qualified names](reference/3.1-execution-semantics.md#31142-qualified-names) inside container states. Empty before the first tick. An object leaves it when its instance leaves |
| `stateMachines` | For each object with a state machine instance, the name of its definition, `null` for an attached state machine. Empty before the first start |
| `roots` | The objects at the top of the model, written like the `value` of the message `objectChanged` |

In the phase `configuring` the snapshot is the uploaded model at revision 0. Without a model the route answers 409 with
`Environment with key <envKey> has no model`. In the other phases the runtime reads the model between two ticks. It
also reads it while a breakpoint halts the run. If no tick ends within 10 s, the route answers 409 with
`Environment with key <envKey> did not reach a tick boundary within 10000 ms`. A model in which an object reaches
itself over its relations cannot be written as JSON. For such a model the route answers 409 with
`Reference loop through object '<key>' cannot be written as JSON`.

`POST /observatory/subscribeModel` with `envKey` and `clientId` makes the client get `objectChanged` and
`objectDeleted` for every object of the model and `stateChange` for every state machine. The client must already be
connected to the WebSocket of the environment when it subscribes. Otherwise the route answers 409 with
`Client <clientId> has no open WebSocket session for environment <envKey>`. The subscription stays when the client
connects again later. `POST /observatory/unsubscribeModel` with the same parameters ends the subscription and answers
200 also when there was none. Both routes work in every phase.
[Keeping a copy of the model](websocket.md#keeping-a-copy-of-the-model) shows how a client keeps a copy
of the whole model with a snapshot and these messages.

`GET /observatory/metrics` with `envKey` answers how many ticks the environment has run and how long they took. It
also counts the calls of the code of `EVAL` blocks and macros for each of the ways of running code that
[3.1.6.3 Where code runs](reference/3.1-execution-semantics.md#3163-where-code-runs) explains. The answer is JSON
with the content type `application/json`.

The runtime counts in every run, whether a client asks for the numbers or not. Reading them does not wait for the
end of a tick. The route answers at once while the environment runs, while it is stopped and while a breakpoint
halts it.

This answer came after three ticks, in which seven calls were computed by the runtime and four ran in Python.

```json
{"environmentKey":"env-1763035112345","phase":"running","ticks":3,"tickMs":{"total":58.307912,"mean":19.435971,"max":58.127,"recent":[58.127,0.051904,0.129008]},"pythonMs":57.686084,"outsidePythonMs":0.621828,"calls":{"computed":{"count":7,"ms":0.071204},"openProcess":{"count":4,"ms":57.686084},"cache":{"count":0,"ms":0}},"resultCaching":true}
```

| Entry | Meaning |
|---|---|
| `environmentKey`, `phase` | As in `GET /observatory/model` |
| `ticks` | The number of ticks since the first start |
| `tickMs` | The durations of the ticks. `total`, `mean` and `max` cover all ticks. `recent` holds the last 100 ticks from the oldest to the newest. The pause of `setTickDelay` between two ticks does not count |
| `pythonMs` | The time the ticks waited for the Python processes of the environment |
| `outsidePythonMs` | The time the ticks spent with everything else, such as finding transitions, writing values into code, reading results, actions and events |
| `calls` | For each way of running code, the number of calls `count` and the time `ms` they took together. The ways are `computed` by the runtime itself, `openProcess` in a Python process of the environment, and `cache` for a call answered from the result cache |
| `resultCaching` | `true` when the result cache of [3.1.6.6 The result cache](reference/3.1-execution-semantics.md#3166-the-result-cache) is on for the environment |

All durations are milliseconds with fractions, measured to the nanosecond. A call that fails is counted like one that
succeeds. The time of a call covers only the run of its code, while writing the values into the code before the call
and reading the result after it count as time outside Python.

With the feature TrueParallel the branches of a split wait for Python at the same time. `pythonMs` adds up the waits of
all branches and can therefore be larger than the time of the ticks in which they waited. A tick whose waits together
are longer than the tick itself adds nothing to `outsidePythonMs`.

The numbers are kept by the engine of the environment. Since `runEnvironment` builds the engine, the route answers
409 with `Environment with key <envKey> is not active` before that call. When the environment is dropped, its
numbers are gone together with its engine.

## Health

`GET /health` answers `{"status":"ok"}` with the content type `application/json` while the runtime runs.

## Features

| Route | Parameters | Answer |
|---|---|---|
| `GET /getRegisteredFeatures` | | The names of all features, separated by commas |
| `GET /getActiveFeatures` | `envKey` | The names of the features on in the environment, separated by commas |
| `POST /activateFeature` | `envKey`, `featureName` | Empty |
| `POST /deactivateFeature` | `envKey`, `featureName` | Empty |
| `POST /sendMessageToFeature` | `envKey`, `featureName`, the message as body | The answer of the feature |

The features and the messages they take are described in [3.2 Runtime features](reference/3.2-features.md). All five
routes work in every phase. The order of the names in the answers of `getRegisteredFeatures` and
`getActiveFeatures` is not fixed. A call that does not fit is refused with status 400 and one of these texts.

| Text | When |
|---|---|
| `Feature '<name>' is not registered` | The name is no feature |
| `Feature '<name>' is already active in environment <envKey>` | `activateFeature` for a feature that is on |
| `Feature '<name>' is not active in environment <envKey>` | `deactivateFeature` for a feature that is off |
| `<name> has not been activated in environment <envKey>` | `sendMessageToFeature` to a feature that is off |

`getRegisteredFeatures` lists all eight features.

```bash
curl "http://127.0.0.1:8080/getRegisteredFeatures"
```

```text
History,TickLimit,TickByTick,Breakpoint,EventRecording,EventInjection,EventReplayController,TrueParallel
```

## Whole session

This session runs the lamp example project, steps through it and watches the bulb. It is run from the folder
`examples/lamp` of the IDE.

```bash
R=http://127.0.0.1:8080
ENV=$(curl -s -X POST "$R/createEnvironment")
curl -X PUT "$R/setMetamodel?envKey=$ENV" --data-binary @lamp.kmeta
curl -X PUT "$R/setModel?envKey=$ENV" --data-binary @lamp.kmodel
curl -X PUT "$R/setEventDefinitions?envKey=$ENV" --data-binary @lamp-events.kmeta
curl -X PUT "$R/setStateMachine?envKey=$ENV&attachedTo=lamp" --data-binary @lamp.kstates
curl -X PUT "$R/setStateMachine?envKey=$ENV&attachedTo=wallSwitch" --data-binary @switch.kstates
curl -X POST "$R/setTickDelay?envKey=$ENV&milliseconds=500"
curl -X POST "$R/setEventTtl?envKey=$ENV&ttlMs=5000"
KEY=$(curl -s -X POST "$R/registerClientForWebSocket?clientId=panel&envKey=$ENV")
curl -X POST "$R/registerObjectObserver?envKey=$ENV&clientId=panel&objectId=bulb"
curl -X POST "$R/activateFeature?envKey=$ENV&featureName=TickByTick"
curl -X POST "$R/runEnvironment?envKey=$ENV"
```

Now the client `panel` connects to `ws://127.0.0.1:8080/ws` and sends `panel:<ENV>:<KEY>` as its first frame. After
that the environment is started and paused at once.

```bash
curl -X POST "$R/startEnvironment?envKey=$ENV"
curl -X POST "$R/sendMessageToFeature?envKey=$ENV&featureName=TickByTick" -d "pause"
```

The client sends a press over the WebSocket.

```json
{"environmentKey": "switch", "messageType": "press", "payload": ""}
```

The run then advances two ticks, the client reads the snapshot, and the environment is stopped.

```bash
curl -X POST "$R/sendMessageToFeature?envKey=$ENV&featureName=TickByTick" -d "tick:2"
curl "$R/observatory/model?envKey=$ENV"
curl -X POST "$R/stopEnvironment?envKey=$ENV"
```

Within the two ticks the lamp switches on. The client gets `objectChanged` for `bulb` after the ENTRY block of `on`
with `lit` true and after the ENTRY block of `full` with `watts` 40. The snapshot shows `mode` `on` and the stack
`on`, `full` of the lamp.

When the client no longer needs the run, it drops the environment. Its WebSocket connection then closes with code
1000 and the reason `Environment dropped`.

```bash
curl -X POST "$R/dropEnvironment?envKey=$ENV"
```

## Messages to features

This part describes the messages that a client sends to the features of
[3.2 Runtime features](reference/3.2-features.md) and the answers it gets. What each feature does in a run is
described in the chapter. The examples talk to the environment of the lamp example project.

A client talks to a feature with `sendMessageToFeature`. The message is the body of the request, as text. The body of
the answer holds what the feature answers. History, TickLimit and TrueParallel take no messages and answer
every message with an empty body.

### Switching features

Here a client switches TickByTick on and asks it for its status, by the rules of
[3.2.1 Switching features](reference/3.2-features.md#321-switching-features).

```bash
curl -X POST "http://127.0.0.1:8080/activateFeature?envKey=env-1763035112345&featureName=TickByTick"
curl -X POST "http://127.0.0.1:8080/sendMessageToFeature?envKey=env-1763035112345&featureName=TickByTick" -d "status"
```

```text
running
```

### TickByTick

These are the messages of [3.2.4 TickByTick](reference/3.2-features.md#324-tickbytick).

| Message | Answer | What happens |
|---|---|---|
| `pause` | `execution paused` | The run waits before its next tick |
| `resume` | `execution resumed` | The run goes on |
| `tick:N` | `execution is advancing N tick(s)` | The paused run does N more ticks and waits again. The answer says `tick` for 1 and `ticks` for more |
| `status` | `paused` or `running` | |

`tick:N` works only while the run is paused and no earlier `tick:N` is still under way. Otherwise the answer is
`execution is currently not paused, ticks will not be applied`. With N less than 1 the answer is
`amount of ticks must not be less than one`. Any other message is refused with status 400 and the text
`Invalid message: <message>`.

A client that wants to step from the first ticks on sends `pause` after the start, because the first start of an
environment resumes a paused run. A `resume` or `tick:N` sent while the environment is stopped takes effect only
after the start. While the run waits at a breakpoint, `resume` continues it and `tick:1` takes it on to the next
breakpoint or to the start of the next tick, whichever comes first.

Two messages pause the run and let it do three more ticks.

```bash
curl -X POST "http://127.0.0.1:8080/sendMessageToFeature?envKey=env-1763035112345&featureName=TickByTick" -d "pause"
curl -X POST "http://127.0.0.1:8080/sendMessageToFeature?envKey=env-1763035112345&featureName=TickByTick" -d "tick:3"
```

```text
execution paused
execution is advancing 3 ticks
```

### Breakpoint

Every message to Breakpoint is a JSON object whose entry `action` says what to do. The further entries depend on the
action. The overrides that these messages set are described in
[3.2.5.2 Breakpoints in the text and overrides](reference/3.2-features.md#3252-breakpoints-in-the-text-and-overrides).

| Action | Further entries | Answer |
|---|---|---|
| `addStateOverride` | `modelElementId`, `stateIdentifier`, `timing` (`entry` or `exit`), `breakpointStatus` (`true` or `false`) | `Breakpoint override has added a breakpoint on state <state> entry in statemachine attached to <object>`, with `removed` for `false` and `exit` for the exit |
| `removeStateOverride` | `modelElementId`, `stateIdentifier`, `timing` | `Breakpoint override on state <state> entry has been removed in statemachine attached to <object>` |
| `addTransitionOverride` | `modelElementId`, `transitionIdentifier`, `timing` (`start` or `end`), `breakpointStatus` | `Breakpoint override has added a breakpoint on transition <transition> start in statemachine attached to <object>` |
| `removeTransitionOverride` | `modelElementId`, `transitionIdentifier`, `timing` | `Breakpoint override on transition <transition> start has been removed in statemachine attached to <object>` |
| `getOverrides` | | The overrides in four groups, as text |
| `getStatus` | | The status of the run as JSON, given in [Breakpoint status](#breakpoint-status) |

`modelElementId` is the key of the object the state machine instance runs on. `stateIdentifier` is the name of the
state. A message the feature cannot carry out gets one of the following answers, all of them with status 200.

| Case | Answer |
|---|---|
| A wrong timing | Starts with `Breakpoint override could not be added` or `Breakpoint override could not be removed` |
| An unknown action | `Invalid action: <action>` |
| A message that is no JSON or lacks an entry | Starts with `Error when parsing the message:` |

`transitionIdentifier` names the transition in the form in which the runtime writes it. For
`TRANSITION "off" -> "full"` with an `EVENT` clause the identifier is this text.

```text
Transition(fromState=off, toState=full, allowLoops=true, startBreakpoint=false, endBreakpoint=false, condition=EVENT)
```

| Part | Value |
|---|---|
| `allowLoops` | `false` for a transition marked `NOT LOOPING` |
| `startBreakpoint`, `endBreakpoint` | Whether the text marks the breakpoint at the start and at the end |
| `condition` | `VALUE` for a transition without `CONDITION`. `EVENT`, `EVAL` or `VALUE` for a condition of one clause, and `COMPOSITE` for a condition of more clauses |

The identifier of a split begins with `SplitTransition(fromState=off, toStates=[test bulb, test switch], ...` and the
one of a join with `JoinTransition(fromStates=[bulb ok, switch ok], toState=on, ...`. Transitions with the same
identifier share their overrides. The entry `halt` of the status in [Breakpoint status](#breakpoint-status) names a
transition in the same form.

The answer of `getOverrides` is plain text and no JSON. It has the form
`{Entry Breakpoints={...}, Exit Breakpoints={...}, Start Breakpoints={...}, End Breakpoints={...}}`. Inside a group
the key of an object leads to its states or transitions with an override, each with `true` or `false`.

This message switches on a breakpoint at the entry of the state `on` of the lamp, and the feature confirms it.

```json
{
  "action": "addStateOverride",
  "modelElementId": "lamp",
  "stateIdentifier": "on",
  "timing": "entry",
  "breakpointStatus": true
}
```

```text
Breakpoint override has added a breakpoint on state on entry in statemachine attached to lamp
```

After this message `getOverrides` lists the one override in the group of the entry breakpoints.

```text
{Entry Breakpoints={lamp={on=true}}, Exit Breakpoints={}, Start Breakpoints={}, End Breakpoints={}}
```

### Breakpoint status

`getStatus` answers with a JSON object. The WebSocket message
[engineStatus](websocket.md#enginestatus) carries the same object as its payload.

| Entry | Meaning |
|---|---|
| `paused` | `true` from the moment a pause is asked for until the run is resumed |
| `waiting` | `true` while the run waits |
| `halt` | There only while the run waits at a breakpoint. It names the place |

At a state, `halt` holds `modelElementId` with the key of the object, `timing` with `entry` or `exit`, and `state`
with the name of the state. At a transition it holds `modelElementId`, `timing` with `start` or `end`, and in
`transition` the identifier of the transition. Four more entries describe the transition.

| Entry | Meaning |
|---|---|
| `kind` | `TRANSITION`, `SPLIT` or `JOIN` |
| `from`, `to` | The source states and the target states, as JSON arrays |
| `index` | The place of the transition in the `TRANSITIONS` block of its file, counted from 0. A timeout and the step into a remembered state have the index -1 |

The order of the entries in these objects is not fixed. This is a halt at the entry of `on`.

```json
{"paused":true,"waiting":true,"halt":{"modelElementId":"lamp","timing":"entry","state":"on"}}
```

A halt at the start of the transition from `off` to `full` gives this status.

```json
{"paused":true,"waiting":true,"halt":{"modelElementId":"lamp","timing":"start","transition":"Transition(fromState=off, toState=full, allowLoops=true, startBreakpoint=false, endBreakpoint=false, condition=EVENT)","kind":"TRANSITION","from":["off"],"to":["full"],"index":0}}
```

### EventRecording

These are the messages of [3.2.6 EventRecording](reference/3.2-features.md#326-eventrecording).

| Message | Answer | What happens |
|---|---|---|
| `start` | `recording started` | Recording starts |
| `stop` | `recording stopped` | Recording stops. Events that wait for the next tick go to the state machines at once |
| `get` | The recording as JSON | |
| `clear` | `cleared recorded events` | The recording is emptied |

Any other message gets the answer `invalid message: <message>`.

The recording is a JSON object whose keys are tick numbers. The value of a key is the list of the events of that tick.
In the list each event is a string that holds a JSON object with these entries.

| Entry | Meaning |
|---|---|
| `environmentKey` | The domain of the event |
| `messageType` | The name of the event |
| `payload` | The payload as text |
| `payloadFormat` | `NONE`, `JSON` or `KMODEL` |

The order of the entries is not fixed. This answer to `get` holds one `dim` event in tick 5.

```json
{"5": ["{\"environmentKey\":\"switch\",\"messageType\":\"dim\",\"payload\":\"{\\\"bright\\\": false}\",\"payloadFormat\":\"JSON\"}"]}
```

### EventInjection

These are the messages of [3.2.7 EventInjection](reference/3.2-features.md#327-eventinjection).

| Message | Answer | What happens |
|---|---|---|
| `upload:<list>` | `successfully uploaded events` | Replaces the list |
| `start` | `injection started` | Injection starts |
| `stop` | `injection stopped` | Injection stops |
| `get` | The list as JSON | |
| `clear` | `cleared event queue` | The list is emptied |

Any other message gets the answer `invalid message: <message>`.

The list behind `upload:` has the form of a recording. An event in it may be a string that holds a JSON object, as in
a recording, or the JSON object itself. Every event needs `environmentKey`, `messageType` and `payload`, while
`payloadFormat` may be left out. A list that cannot be read is refused with an error status and the reason as
text. The list uploaded before stays in place.

A client uploads a list with one press in tick 3 and starts the injection.

```bash
curl -X POST "http://127.0.0.1:8080/sendMessageToFeature?envKey=env-1763035112345&featureName=EventInjection" \
  -d 'upload:{"3": [{"environmentKey": "switch", "messageType": "press", "payload": ""}]}'
curl -X POST "http://127.0.0.1:8080/sendMessageToFeature?envKey=env-1763035112345&featureName=EventInjection" -d "start"
```

```text
successfully uploaded events
injection started
```

### EventReplayController

[3.2.8 EventReplayController](reference/3.2-features.md#328-eventreplaycontroller) takes one message.

| Message | Answer | What happens |
|---|---|---|
| `initialize` | `replay successfully initialized` | Stops recording and injection and uploads the recording as the list of EventInjection |

Any other message gets the answer `invalid message: <message>`. If the upload of the recording fails, the call is
refused with an error status and the reason as text. Recording and injection are stopped by then.

### TrueParallel

This call switches on [3.2.9 TrueParallel](reference/3.2-features.md#329-trueparallel), which takes effect at the
start of the next tick.

```bash
curl -X POST "http://127.0.0.1:8080/activateFeature?envKey=env-1763035112345&featureName=TrueParallel"
```

## Running an example

These are the steps of [3.6 Examples](reference/3.6-examples.md) for the autonomous car in the folder
`examples/autonomous_car_example`. In that folder the client creates an environment and uploads the files.

```bash
ENV=$(curl -s -X POST http://127.0.0.1:8080/createEnvironment)
curl -X PUT "http://127.0.0.1:8080/setMetamodel?envKey=$ENV" --data-binary @autonomous_car.kmeta
curl -X PUT "http://127.0.0.1:8080/setModel?envKey=$ENV" --data-binary @autonomous_car.kmodel
curl -X PUT "http://127.0.0.1:8080/setEventDefinitions?envKey=$ENV" --data-binary @EVENTS.kmeta
curl -X PUT "http://127.0.0.1:8080/setStateMachine?envKey=$ENV&attachedTo=av-1" --data-binary @autonomous_car.kstates
curl -X POST "http://127.0.0.1:8080/setTickDelay?envKey=$ENV&milliseconds=300"
```

It registers the WebSocket client `viewer` and makes it an observer of the car.

```bash
KEY=$(curl -s -X POST "http://127.0.0.1:8080/registerClientForWebSocket?clientId=viewer&envKey=$ENV")
curl -X POST "http://127.0.0.1:8080/registerObjectObserver?envKey=$ENV&clientId=viewer&objectId=av-1"
```

It runs and starts the environment.

```bash
curl -X POST "http://127.0.0.1:8080/runEnvironment?envKey=$ENV"
curl -X POST "http://127.0.0.1:8080/startEnvironment?envKey=$ENV"
```

After the client has connected to `ws://127.0.0.1:8080/ws` and sent `viewer:<ENV>:<KEY>` as its first frame, this
event starts the car.

```json
{"environmentKey": "ignition", "messageType": "powerOn", "payload": ""}
```
