# State Machine Execution Semantics

This document describes how the Karpfen execution engine interprets and runs state machines at runtime. It focuses on non-trivial behavior that users must understand to write correct `.kstates` definitions.

---

## Tick-Based Execution

The engine operates in a discrete tick loop. Each tick proceeds through two sequential phases for every attached state machine:

1. **ENTRY phase** – execute `onEntry` blocks for newly entered states (top-down)
2. **DO phase** – execute the `onDo` block of the innermost (current) state

After each phase, the engine checks for fireable transitions. A configurable `tickDelay` (in milliseconds) pauses between ticks, but the actual tick duration is dominated by action execution time (especially EVAL/MACRO calls which spawn Python subprocesses).

## State Stack and Hierarchical States

States can be nested. The engine maintains a state stack - an ordered list from the outermost to the innermost active state.

```
Example: stack = [drive, drive fast]
                  ↑ outer    ↑ inner (current)
```

- **ENTRY** is executed top-down for every state in the stack that was newly entered.
- **DO** is executed only for the innermost state.
- Transitions are checked from innermost to outermost - the innermost state has priority.

### Initial State

At startup, the engine resolves the initial stack by recursively following states marked `INITIAL`. If no state is marked initial at a given level, the first defined state is used.

---

## Transition Evaluation

### Evaluation Order

Transitions are evaluated in definition order within the `TRANSITIONS` block. The first transition whose condition evaluates to `true` (and is not blocked by NOT LOOPING) fires. Definition order is therefore a priority mechanism.

```
TRANSITIONS {
    TRANSITION "drive" -> "drive slow" NOT LOOPING { ... }   // checked first
    TRANSITION "drive" -> "drive fast" NOT LOOPING { ... }   // checked second
    TRANSITION "drive" -> "observe" { }                       // fallback (unconditional)
}
```

### When Transitions Are Checked

Transitions are checked at two points during a tick:

1. **After each ENTRY block** - but only if the ENTRY block contained actions. If `onEntry` is empty, the engine skips the transition check and proceeds to DO. This prevents unconditional transitions from firing before DO gets a chance to execute.
2. **After the DO block** of the innermost state.

If a transition fires, the tick ends immediately. The new state's ENTRY will run on the next tick.

### Unconditional Transitions

A transition without a `CONDITION` block is parsed as `VALUE("true")` - it fires unconditionally whenever it is evaluated. These are typically used as fallback transitions at the end of the transition list.

### Condition Types

| Type | Syntax | Behavior |
|------|--------|----------|
| **VALUE** | `CONDITION { VALUE("path") }` | Resolves a boolean model property. Literals `"true"` / `"false"` are constants. |
| **EVAL** | `CONDITION { EVAL { ... } }` | Executes Python code; expects a boolean return value. |
| **EVENT** | `CONDITION { EVENT("domain", "name") }` | Checks the event bus for a matching event. The event that fires the transition is consumed (marked as processed by this engine). |

### Conditions with several clauses

A `CONDITION` block may list more than one clause. They are evaluated top to bottom and **all** of them must hold for the transition to fire (a short-circuit AND). This keeps each individual clause small instead of cramming everything into one big EVAL:

```
TRANSITION "observe" -> "evade" {
    CONDITION {
        EVENT("public", "obstacleDetected")     // an EVENT clause, if present, comes first
        EVAL { return $(event->distance) < 100 } // a guard on the event payload
        EVAL { return $(d_closest_wall) > 100 }  // a guard on the model
    }
}
```

At most one clause may be an `EVENT` clause, and it has to come first, because it is what brings the event into scope for the guards that follow it (see [Reading event payloads](#reading-event-payloads)). A single-clause `CONDITION` behaves exactly as before, so existing state machines are unaffected.

---

## NOT LOOPING

A transition marked `NOT LOOPING` will not fire consecutively. If the exact same transition was the last one fired by this state machine context, it is skipped.

```
TRANSITION "drive" -> "drive fast" NOT LOOPING { ... }
```

**Key semantics:**
- NOT LOOPING compares the transition object identity, not just from/to names.
- When a NOT LOOPING transition is skipped, the engine continues evaluating the next transition in definition order. This is how fallback transitions (e.g., `drive → observe`) get a chance to fire.
- Once a *different* transition fires, the NOT LOOPING block is reset - the previously blocked transition can fire again on the next evaluation.

**Example cycle:**
```
Tick  9: drive → drive fast   (fires, EVAL=true, NOT LOOPING)
Tick 10: drive → drive fast   (SKIPPED - same as last)
         drive → observe      (fires - unconditional fallback)
Tick 11: observe → drive      (fires - unconditional)
Tick 12: drive → drive fast   (fires again - last was observe→drive)
```

---

## Action Execution

### Action Types

| Operation | Behavior |
|-----------|----------|
| `SET(path, value)` | Writes a value to a model property or relation. |
| `APPEND(path, value)` | Appends a value to a list property. |
| `EVENT(domain, name)` | Publishes an internal event to the shared event bus. |

### Right-Hand Side Value Types

| Type | Syntax | Behavior |
|------|--------|----------|
| **VALUE** | `SET("y", VALUE("8.5"))` | Literal string, auto-parsed to the target property type (number, boolean, string). |
| **EVAL** | `SET("y", EVAL { ... })` | Inline Python code. The `return` value is parsed according to the target type. If the result is `null`, the SET is silently skipped. |
| **MACRO** | `SET("x", MACRO("name", args...))` | Executes a named macro defined in the `MACROS` block. Arguments are model paths resolved from the context object. |

### Model References in EVAL / MACRO

`$(path)` references inside EVAL blocks are resolved at code-generation time (before Python execution). The current model values are substituted as Python literals:

```
SET("y", EVAL { return $(boundingBox->position->y) + 0.3 * $(direction->y) })
```

This means `$(boundingBox->position->y)` is replaced by the current value of `y` each time the EVAL is executed. Changes from previous ticks are visible.

A path may also start with `event`, in which case it reads from the payload of the event currently in scope rather than from the context object — see [Reading event payloads](#reading-event-payloads).

### IF IN SCOPE blocks

A state can be reached in different ways: through an event transition (which leaves a scoped event behind) or through a plain transition (which does not). An `IF IN SCOPE(...)` block lets one ENTRY or DO block adapt to that, running its body only when every path it names currently resolves to a value:

```
DO {
    IF IN SCOPE("event->factor") {
        SET("speed", EVAL { return 0.3 * $(event->factor) })
    }
    APPEND("log", "still driving")
}
```

The check is about availability, not truth. A path rooted at `event` is available only when an event is in scope and actually carries that field; any other path is available when it resolves against the context object. You can list several paths separated by commas, and all of them must be available for the body to run. Blocks can be nested.

---

## Event System

### Domains

Events are organized into domain buckets (e.g., `"public"`, `"local"`). Domains are created lazily when the first event with a new domain name is published.

### Event Sources

| Source | Origin |
|--------|--------|
| `INTERNAL` | Produced by an `EVENT(...)` action inside a state machine. |
| `EXTERNAL_ENGINE` | Produced by another engine running in a separate thread. |
| `EXTERNAL_MESSAGE` | Arrived from an outside client via WebSocket. |

### Time-To-Live (TTL)

Every event has a TTL. Once `currentTime - event.timestamp > ttlMs`, the event is expired and ignored. Expired events are purged once per tick. Events with `ttlMs = 0` never expire.

### Event Consumption Model

Events are not deleted when consumed. Instead, the consuming engine's ID is recorded on the event. This means:

- Multiple engines can independently react to the same event.
- The same engine will not react to the same event twice.
- The event dies naturally when its TTL expires.

Consumption follows run-to-completion semantics. On each tick an event is offered to every transition of the current state configuration. If one of them fires on it, that event is consumed. If the whole configuration is tried and nothing reacts, the event is consumed anyway (it was dispatched and ignored, so it is not retried on the next tick). The only events that survive a tick are those whose name no transition in the current configuration listens for — they wait, until either the machine moves into a state that does listen, or their TTL runs out.

### Event payloads

An event can carry a structured payload, not just a name. The shape of each payload is described in a separate kmeta file (conventionally `EVENTS.kmeta`) and registered with `PUT /setEventDefinitions`. Every type in that file is also an event name: an event named `setSpeed` is parsed against the `setSpeed` type. Payloads can be sent as JSON or in the kmodel syntax — both are turned into the same kind of runtime object, so from the state machine's point of view it makes no difference which was used. A payload may also embed domain types (for example an event carrying a `Vector`), because the domain metamodel is made available when the event definitions are parsed.

#### Reading event payloads

You never address an arbitrary event from the queue. Instead, the event that causes a reaction is the one you can read. When a transition with an `EVENT` clause fires, that event becomes the **scoped event** of the state being entered, and you read its payload with paths rooted at `event`:

```
SET("speed", EVAL { return 0.3 * $(event->factor) })
```

The same `$(event->...)` paths work inside the guard clauses of the transition itself (the `EVENT` clause binds the candidate before the guards run), which is how you filter on payload content — for instance, only react to a `setSpeed` whose `factor` is above some threshold. When several events of the same name are waiting, they are tried oldest-first, and the first one whose guards all pass is the one that fires.

### Scoped event lifetime

A scoped event stays alive for as long as its state stays active. Concretely:

- Entering a state through an event transition scopes that event to the state.
- The scope is dropped when the state is left.
- Entering a *substate* through its own event transition overwrites the scope; leaving that substate reverts to the enclosing event.
- A substate entered through a plain (non-event) transition inherits the enclosing event — it does not clear it.

Because the engine holds onto the event object for the lifetime of the state, the payload remains readable even after the event has been consumed and even after its TTL would have removed it from the bus. In other words, scoping captures the payload into the state. If you need a value to outlive the state, write it into the model from an ENTRY/DO action.

### Shared Event Bus

All engines within the same environment share a single `EventBus`. Events published by one engine are immediately visible to all others. The bus uses `ConcurrentHashMap` and `CopyOnWriteArrayList` for thread safety.

---

## Threading Model

- Each environment runs its engine on a dedicated thread.
- Multiple state machines attached to objects within the same environment are executed sequentially within one tick (not in parallel).
- The model is written only by the engine thread. Observer callbacks (e.g., WebSocket notifications) are dispatched asynchronously.
- The `EventBus` is the only shared mutable data structure between engine threads and is fully thread-safe.

---

## Error Handling

- If an `onEntry` or `onDo` action block throws an exception, the error is logged (via the trace logger and stderr) but the engine continues. The tick proceeds normally.
- If a transition target state cannot be found, the transition is skipped and an error is logged.
- If an EVAL or MACRO Python subprocess fails (non-zero exit code), a `RuntimeException` is thrown, caught by the action block error handler, and logged.
- If a VALUE condition path cannot be resolved, the condition evaluates to `false`.

---

## Trace Logging

When an `EngineTraceLogger` is configured, the engine writes structured trace entries for every significant event:

| Event Type | Description |
|-----------|-------------|
| `INITIAL_STATE` | The initial state stack at startup. |
| `ENGINE_START / ENGINE_STOP` | Engine lifecycle. |
| `TICK_START / TICK_END` | Tick boundaries with current state and stack. |
| `STATE_ENTRY_EXEC / STATE_ENTRY_SKIP` | ENTRY block execution or skip (empty block). |
| `STATE_DO_EXEC / STATE_DO_SKIP` | DO block execution or skip. |
| `TRANSITION_FIRED` | A transition was applied (includes from, to, condition type, stacks). |
| `TRANSITION_SKIPPED_LOOP` | A NOT LOOPING transition was blocked. |
| `ACTION_ERROR / ENGINE_ERROR` | Errors during execution. |

Traces can be written to a log file for post-mortem analysis.

