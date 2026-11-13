# Features

A feature is a part of the engine that an environment can switch on and off while it exists. Without any feature the
engine runs as described in the [execution-semantics guide](statemachine_execution_semantics.md). This guide lists
the features, how to switch them on, and the messages each one understands.

## The features

| Name | Kind | What it does | Switches on as well |
|------|------|--------------|---------------------|
| `History` | language | Applies `SHALLOW HISTORY` and `DEEP HISTORY`. | |
| `TickLimit` | language | Applies the `DELAY` and `TIMEOUT` of `TICK LIMIT` blocks. | |
| `TickByTick` | runtime | Pauses the engine and runs it a number of ticks at a time. | |
| `Breakpoint` | runtime | Halts the engine at the breakpoints of states and transitions. | `TickByTick` |
| `EventRecording` | runtime | Records the events that clients send, with the tick they arrived in. | |
| `EventInjection` | runtime | Publishes a list of events, each in its tick. | |
| `EventReplayController` | runtime | Hands a recording to the injection. | `EventRecording`, `EventInjection` |

The language features give meaning to words of a `.kstates` file. The semantics guide describes
[histories and tick limits](statemachine_execution_semantics.md#histories-tick-limits-and-breakpoints). The runtime
features are tools for running and debugging.

## Switching features on and off

Every environment has its own features. They can be switched from the moment the environment is created, before and
while it runs.

| Method | Endpoint | Answer |
|--------|----------|--------|
| GET | `/getRegisteredFeatures` | The names of all features, separated by commas. |
| GET | `/getActiveFeatures?envKey=...` | The names of the features that are on in the environment. |
| POST | `/activateFeature?envKey=...&featureName=...` | Empty. `400` if the feature is on already or unknown. |
| POST | `/deactivateFeature?envKey=...&featureName=...` | Empty. `400` if the feature is not on or unknown. |
| POST | `/sendMessageToFeature?envKey=...&featureName=...` | The answer of the feature to the message in the request body. `400` if the feature is not on. |

- Switching a feature on switches on the features it needs first. `Breakpoint` brings `TickByTick` with it.
- Switching a feature off also switches off every feature that needs it. A feature that was only switched on
  because another one needed it goes off with the last feature that needs it.
- Two switches at the same moment do not wait for each other: the second one is refused with `409`.
- When the engine of an environment starts, a paused engine is resumed, and the tick counts and the histories are
  forgotten. The features themselves stay on, and so do the breakpoint overrides and the recorded events.

```bash
curl -X POST "http://localhost:8080/activateFeature?envKey=env-123&featureName=History"
curl -X POST "http://localhost:8080/sendMessageToFeature?envKey=env-123&featureName=TickByTick" -d "pause"
```

## TickByTick

| Message | Effect | Answer |
|---------|--------|--------|
| `pause` | The engine finishes the tick it is in and then waits. | `execution paused` |
| `resume` | The engine runs freely again. | `execution resumed` |
| `tick:N` | A waiting engine runs `N` ticks and waits again. `N` is at least 1. | `execution is advancing N ticks` |
| `status` | Nothing. | `paused` once a pause was asked for or a breakpoint halted the engine, until it is resumed. Otherwise `running`. |

`tick:N` only works while the engine waits and has no ticks left from an earlier `tick:N`. Otherwise the answer says
that the ticks are not applied. Any other message is answered with `400`. Switching the feature off resumes the
engine, and so does stopping the environment.

## Breakpoint

A breakpoint halts the engine thread in the middle of a tick. While it is halted the model can be read: a snapshot
(`GET /observatory/model`) shows the model as the halted tick has left it so far, with every `ENTRY` or `DO` block
that ran complete. The active stacks of the snapshot are still those of the last finished tick, because a stack
change is announced when its tick ends.

The engine halts at these places:

| Breakpoint | Written as | Where the engine halts |
|------------|------------|------------------------|
| State entry | `BREAKPOINT { ENTRY }` in a state | Before the `ENTRY` block of the state, also when the block is empty. |
| State exit | `BREAKPOINT { EXIT }` in a state | When a transition that leaves the state is applied, before the transition starts. With several states left, the innermost comes first. |
| Transition start | `BREAKPOINT { START }` in a transition | Before the transition changes the stack. |
| Transition end | `BREAKPOINT { END }` in a transition | After the transition changed the stack, in the same tick. |

A transition that a `DELAY` holds back halts nowhere. A halted engine goes on with the messages of `TickByTick`:
`resume` runs freely to the next breakpoint, and `tick:1` runs to the next breakpoint or to the end of the tick,
whichever comes first.

Breakpoints can also be set and removed while the environment runs, without changing the `.kstates` file. Such an
override wins over what the file says for the same place. The messages are JSON objects:

| `action` | Other members | Effect |
|----------|---------------|--------|
| `addStateOverride` | `modelElementId`, `stateIdentifier`, `timing` (`entry` or `exit`), `breakpointStatus` | `true` sets a breakpoint, `false` switches off one that the file sets. |
| `addTransitionOverride` | `modelElementId`, `transitionIdentifier`, `timing` (`start` or `end`), `breakpointStatus` | The same for a transition. |
| `removeStateOverride` | `modelElementId`, `stateIdentifier`, `timing` | The file decides again. |
| `removeTransitionOverride` | `modelElementId`, `transitionIdentifier`, `timing` | The file decides again. |
| `getOverrides` | | Lists the overrides. |
| `getStatus` | | Answers what the engine is doing, for a debugger. See below. |

`modelElementId` is the id of the object the state machine is attached to, and `stateIdentifier` is the name of the
state. `transitionIdentifier` is the text form of the transition as the engine prints it, for example
`Transition(fromState=cars wait, toState=cars go, allowLoops=true, startBreakpoint=true, endBreakpoint=true, condition=EVENT)`.
A split starts with `SplitTransition(fromState=..., toStates=[..., ...],` and a join with
`JoinTransition(fromStates=[..., ...], toState=...,`. The condition is named by its kind only, `VALUE`, `EVAL`,
`EVENT` or `COMPOSITE`, and a transition without a condition is a `VALUE`. Two transitions with the same text form
share their overrides.

```bash
curl -X POST "http://localhost:8080/sendMessageToFeature?envKey=env-123&featureName=Breakpoint" \
  -d '{"action": "addStateOverride", "modelElementId": "crossing", "stateIdentifier": "cars go", "timing": "entry", "breakpointStatus": true}'
```

The answer to every other message is a sentence that says what was done, or what was wrong with the message.

`getStatus` answers a JSON object:

| Member | Meaning |
|--------|---------|
| `paused` | `true` once a pause was asked for or a breakpoint halted the engine, until it is resumed. |
| `waiting` | `true` while the engine thread waits right now, between two ticks or at a breakpoint. |
| `halt` | Only while the engine waits at a breakpoint: where it waits. |

A `halt` has the `modelElementId` of the state machine and the `timing`, which is `entry`, `exit`, `start` or `end`.
For a state it has `state`, the name of the state. For a transition it has:

| Member | Meaning |
|--------|---------|
| `transition` | The identifier of the transition, as the overrides take it. |
| `kind` | `TRANSITION`, `SPLIT` or `JOIN`. |
| `from`, `to` | The names of its source and target states. |
| `index` | Its place among the transitions of the `.kstates` file, counted from 0. It is -1 for a transition the engine made up for a timeout or a history. |

```json
{"paused": true, "waiting": true, "halt": {"modelElementId": "crossing", "timing": "entry", "state": "cars wait"}}
```

An engine that waits between two ticks, after `pause` or after the ticks of a `tick:N`, is `paused` and `waiting`
and has no `halt`.

## EventRecording, EventInjection and EventReplayController

Together these three replay the events of a run in a later run of the same environment, each event in the tick it
arrived in the first time. Ticks are counted from the start of the engine, beginning with 1.

**EventRecording** records the events that clients send over the WebSocket. Events that state machines raise with an
`EVENT` action are not recorded, because a replayed run raises them again by itself.

| Message | Effect |
|---------|--------|
| `start` | From now on an arriving event waits for the start of the next tick. It is published then and recorded under the number of that tick. |
| `stop` | Ends the recording and publishes the events that still wait. Switching the feature off does the same. |
| `get` | Answers the recording as JSON. |
| `clear` | Forgets the recording. |

The recording is a JSON object. Each key is a tick number, and its value is a list of events. Each event is a string
that holds a JSON object with the members `environmentKey` (the domain of the event), `messageType` (its name),
`payload` and `payloadFormat`.

**EventInjection** publishes events in the ticks they are listed for.

| Message | Effect |
|---------|--------|
| `upload:<recording>` | Replaces the list of events with a recording in the form that `get` answers. |
| `start` | From now on, at the start of each tick, the events listed for that tick and for earlier ticks are published and taken off the list. |
| `stop` | Ends the injection. The events left on the list stay there. |
| `get` | Answers the events left on the list. |
| `clear` | Empties the list. |

**EventReplayController** has one message, `initialize`. It stops the recording and the injection and uploads the
recording to the injection.

A replay goes like this:

1. Switch `EventReplayController` on and send `start` to `EventRecording`.
2. Run the environment and send it events.
3. Send `initialize` to `EventReplayController`, then stop the environment.
4. Send `start` to `EventInjection` and start the environment again. Its ticks count from 1 again, so each event is
   published in the tick it was recorded in.

The model is not reset by a restart, so a replay only repeats the first run if the state machines bring the model
back to where it started, or if the replay runs in a new environment that got the recording with `upload:`.
