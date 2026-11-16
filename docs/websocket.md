# The WebSocket of the runtime

This document is the complete description of the WebSocket for people who write a client of the runtime. Over the
WebSocket at `ws://127.0.0.1:8080/ws` clients send events to an environment and get its changes pushed. Host and port
are those set in [3.3.4 Configuration](reference/3.3-running-the-runtime.md#334-configuration). All messages are text
frames. The IDE connects on its own, so a user of the IDE does not need the document. A short overview is in
[3.5 WebSocket](reference/3.5-websocket.md).

## Connecting

Before it connects, a client registers over HTTP with `registerClientForWebSocket` or `observatory/registerClient` of
the [HTTP API](http-api.md#clients) and gets an access key. Then it opens the WebSocket and sends a first frame with
its client id, the key of the environment and the access key, separated by colons:
`<clientId>:<envKey>:<accessKey>`. The client `panel` connects to the environment `env-1763035112345` with this
frame.

```text
panel:env-1763035112345:ak-panel-1763035113001-4821
```

If the first frame is wrong, the runtime closes the connection with code 1003 and one of these reasons.

| Reason | When |
|---|---|
| `First message must be text` | The first frame is no text frame |
| `Invalid auth format` | The frame does not have exactly three parts |
| `Invalid credentials` | The access key is unknown, was used before, or belongs to another client or environment |

The runtime sends no answer to a correct first frame. A client knows that it is connected when
`observatory/subscribeModel` succeeds or when the first message arrives. A client registers again before it
connects again, because each access key works for one connection only. The runtime sends a ping every 60 s and closes a
connection that does not answer within 15 s.

The connections stay open when the environment is stopped and started again. The clients learn of both through
`engineStatus`. Apart from the closes after a wrong first frame and after a
missed ping, the runtime closes the connections of an environment in two cases.

| Code | Reason | When |
|---|---|---|
| 1000 | `Environment dropped` | `POST /dropEnvironment` dropped the environment of the connection |
| 1001 | `Runtime stopped` | The runtime itself ends. Only clients of an environment that was run get this close. A client of an environment that is still being configured just loses its connection |

After either close the access key of the connection is gone. After a drop the environment is gone too. A client that
wants to go on then needs a new environment.

## Sending events

The client sends an event as a text frame that holds a JSON object. This frame sends the event `dim` of the domain
`switch` with a JSON payload.

```json
{"environmentKey": "switch", "messageType": "dim", "payload": "{\"bright\": true}", "payloadFormat": "json"}
```

| Entry | Meaning |
|---|---|
| `environmentKey` | The domain of the event, like `switch` in `EVENT("switch", "dim")`. Despite its name it is not the key of the environment |
| `messageType` | The name of the event |
| `payload` | The payload as a string. Optional, empty by default |
| `payloadFormat` | `none`, `json` or `kmodel`, in any case. Optional |

Without `payloadFormat` the runtime takes the format from the payload itself.

- An empty payload is no payload.
- A text that starts with `{` is JSON.
- A text that starts with `make object` is KModel.

[3.1.7.4 Payloads](reference/3.1-execution-semantics.md#3174-payloads) gives the rules by which the runtime reads the
payload against the event type that is named like the event. A payload in KModel is one `make object` of that type.
Here the same event carries its payload in KModel, written into a JSON string.

```json
{"environmentKey": "switch", "messageType": "dim", "payload": "make object \"step\":\"dim\" { prop(\"bright\") -> \"true\" }"}
```

The event goes to the environment the connection belongs to. A frame that is no JSON object with `environmentKey` and
`messageType` is ignored. Events sent before `runEnvironment` are dropped. Events sent after `runEnvironment` and
before `startEnvironment` wait for the start. The runtime sends no answer to an event.

## Pushed messages

Every message that the runtime pushes has the same outer form.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"objectChanged","payload":{}}
```

| Entry | Meaning |
|---|---|
| `environmentKey` | The key of the environment. In an event that a client sends, the same entry holds the domain |
| `clientId` | The id of the client that gets the message |
| `messageType` | The kind of message, one of those in the table below |
| `payload` | A JSON object whose content depends on `messageType` |

With `websocket.enabled` set to `false` the runtime pushes nothing.

| Message | Who gets it | When |
|---|---|---|
| `objectChanged` | Observers of the object, model subscribers | An action block changed a property or relation of the object |
| `objectDeleted` | Observers of the object, model subscribers | An action removed the object |
| `stateChange` | Clients of `observatory/registerClient` for this state machine or `*`, model subscribers | The stack of a state machine changed, and in the first tick after the first start |
| `stateMachineAttached` | The same clients as `stateChange` of the object | A state machine instance joined. At the first start for every instance, and at the end of a tick for each that joined in it |
| `stateMachineDetached` | The same clients as `stateChange` of the object | A state machine instance left, at the end of the tick in which it ended |
| `domainEvent` | Listeners of the domain | An event of the domain reached the bus |
| `engineStatus` | Every client connected to the environment | The run starts or ends a wait of TickByTick, the environment was stopped, or a stopped environment was started |

### objectChanged

The payload holds `objectId` with the key of the object, `revision` with the revision of [Revisions](#revisions), and
`value`. This message reports the bulb after the ENTRY block of `full` in the lamp example project.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"objectChanged","payload":{"objectId":"bulb","revision":7,"value":{"__id__": "bulb", "__type__": "Bulb", "maxWatts": 40, "watts": 40, "lit": true}}}
```

`value` is the whole object as it is after the action block. It holds the following entries.

- `__id__` with the key of the object
- `__type__` with its type
- its properties in the order of the model, where a property without a value is left out
- its relations with the objects in them, again written whole, also the objects of a `knows` relation

The message names only the object whose own property or relation changed. A change of the bulb gives a message for
`bulb` and none for `lamp`, although the lamp holds the bulb.

A block that changes several objects gives one message per object, after the block.

### objectDeleted

The payload holds `objectId` with the key of the removed object and `revision`. Here the spare bulb was removed.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"objectDeleted","payload":{"objectId":"spare","revision":9}}
```

`DROPOBJ("spare")` gives `objectDeleted` for `spare` and for every object it holds. The lamp that held it gets
`objectChanged`. The `objectChanged` messages come first.

### stateChange

The payload holds `modelElementId`, `stack` and `revision`. In this message the lamp is in `full` inside `on`.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"stateChange","payload":{"modelElementId":"lamp","stack":["on","full"],"revision":8}}
```

`modelElementId` is the key of the object of the state machine. `stack` is its stack from the outside in. In a
split it holds the stacks of all branches one after the other. The order of the entries in `payload` is not fixed.

Inside a container state the stack names the states of the fragment by the names of
[3.1.14.2 Qualified names](reference/3.1-execution-semantics.md#31142-qualified-names). A lamp in the self test of
[2.3.12 Pages](https://github.com/karpfenproject/karpfen-dsl-tools/blob/main/docs/reference/2.3-kstates.md#2312-pages)
reports `"stack":["self test","self test/powered"]`. A stack never ends on a container state.

### domainEvent

The payload holds `domain` with the domain of the event and an inner `payload` with the entries of the table below.
In this message a listener of the domain `switch` learns of the event `dim` that a client sent.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"domainEvent","payload":{"domain":"switch","payload":{"event":"dim","payload":"{\"bright\": true}","payloadFormat":"json","source":"EXTERNAL_MESSAGE","timestamp":1763035114120,"ttlMs":5000}}}
```

| Entry | Meaning |
|---|---|
| `event` | The name of the event |
| `payload` | The payload as the sender wrote it, as a string |
| `payloadFormat` | `none`, `json` or `kmodel` |
| `source` | `EXTERNAL_MESSAGE` for events from clients and from EventInjection, `INTERNAL` for events raised with the action `EVENT` |
| `timestamp` | When the runtime got or raised the event, in milliseconds since 1970 |
| `ttlMs` | The time to live of the event |

The order of the entries inside the inner `payload` is not fixed. A `domainEvent` has no revision.

### engineStatus

TickByTick has paused the run, which now waits.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"engineStatus","payload":{"paused":true,"waiting":true}}
```

The payload is the status of the run that [Breakpoint status](http-api.md#breakpoint-status) describes. While a
breakpoint holds the run, it includes `halt`. An `engineStatus` has no revision.

A stop and the start after it are reported with an additional member `phase` next to the status at that moment.
`phase` is `stopped` or `running`, the same value that the snapshot has. Without TickByTick the status is
`{"paused":false,"waiting":false}`, and a stop and the start after it give these two messages.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"engineStatus","payload":{"phase":"stopped","paused":false,"waiting":false}}
```

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"engineStatus","payload":{"phase":"running","paused":false,"waiting":false}}
```

The `engineStatus` messages of TickByTick have no `phase`. A stop while the run is paused reports `"paused":true` and
`"waiting":true`. So does the start after it, because the run still waits. A client that reads only `paused` and
`waiting` keeps working but does not learn of the stop. The first start of an environment sends no `engineStatus`.

### stateMachineAttached and stateMachineDetached

The payload of both messages holds `modelElementId`, `stateMachine` and `revision`. In these two messages an instance
of `blinking` joined on a new bulb, and the instance of `glowing` on the bulb `bulb` left.

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"stateMachineAttached","payload":{"modelElementId":"gen_Bulb_0","stateMachine":"blinking","revision":12}}
```

```json
{"environmentKey":"env-1763035112345","clientId":"panel","messageType":"stateMachineDetached","payload":{"modelElementId":"bulb","stateMachine":"glowing","revision":15}}
```

`modelElementId` is the key of the object, the same key that `stateChange` uses for the instance. `stateMachine` is
the name of the attachable state machine the instance runs, or `null` for a state machine uploaded with `attachedTo`.
In this way the object names the instance and `stateMachine` the definition it was built from. The order of
the entries in `payload` is not fixed.

At the first start the runtime sends `stateMachineAttached` for every instance before the first tick, in the order in
which the instances step. A start after a stop continues the run and announces nothing anew.

An instance that joins during a run is announced at the end of the tick it joined in, after the `stateChange` messages
of that tick. Its first `stateChange` follows after its first step. An instance that leaves is announced at the end of
the tick in which it ended, before the instances that join in the same tick. After that no `stateChange` comes for it.
When `EXCHANGE` replaces an instance, `stateMachineDetached` with the old definition comes before
`stateMachineAttached` with the new one, for the same object. The `objectDeleted` of a removed object comes earlier, at
the moment the action removes the object.

A client that does not know these messages can ignore them. It still gets `stateChange` for every instance, also for
those that join during the run, but it does not learn when one leaves.

## Revisions

The messages `objectChanged`, `objectDeleted`, `stateChange`, `stateMachineAttached` and `stateMachineDetached` carry a
revision. Each environment counts its revisions from 1 on, with one count for all five messages and all clients, in
the order of the changes. A stop and a start add no revision. The count goes on after them. A change of an object
that cannot be written as JSON, because the object reaches itself over its relations, uses up a revision and sends
nothing.

## Keeping a copy of the model

A client that shows the whole model keeps a copy of it in four steps.

1. Register with `registerClientForWebSocket`, connect and call `observatory/subscribeModel`.
2. Read `observatory/model`. Its `revision` is the last change it contains.
3. Ignore pushed messages with a revision up to the one of the snapshot. Apply the later ones in order, each as the
   table below says.
4. When a revision is missing, read `observatory/model` again.

| Message | Change to the copy |
|---|---|
| `objectChanged` | Replace the object |
| `objectDeleted` | Remove the object |
| `stateChange` | Replace the stack |
| `stateMachineAttached` | Add the object with its definition to `stateMachines` |
| `stateMachineDetached` | Remove the object from `stateMachines` and `activeStates` |

## Delivery

The runtime sends the messages of one client in the order in which they were made. A client that has more than one
connection to the same environment gets each message on one of them. Messages for a client without a connection are
dropped. After 10 s without a connection the runtime also ends a registration that the client made with
`observatory/registerClient`.
