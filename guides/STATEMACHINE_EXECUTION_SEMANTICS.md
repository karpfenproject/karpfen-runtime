# State Machine Execution Semantics

This document describes how the Karpfen execution engine interprets and runs state machines at runtime. It focuses on non-trivial behavior that users must understand to write correct `.kstates` definitions.

---

## Tick-Based Execution

The engine operates in a **discrete tick loop**. Each tick proceeds through two sequential phases for every attached state machine:

1. **ENTRY phase** – execute `onEntry` blocks for newly entered states (top-down)
2. **DO phase** – execute the `onDo` block of the innermost (current) state

After each phase, the engine checks for fireable transitions. A configurable `tickDelay` (in milliseconds) pauses between ticks, but the actual tick duration is dominated by action execution time (especially EVAL/MACRO calls which spawn Python subprocesses).

## State Stack and Hierarchical States

States can be nested. The engine maintains a **state stack** — an ordered list from the outermost to the innermost active state.

```
Example: stack = [drive, drive fast]
         ↑ outer    ↑ inner (current)
```

- **ENTRY** is executed top-down for every state in the stack that was newly entered.
- **DO** is executed **only** for the innermost state.
- Transitions are checked from **innermost to outermost** — the innermost state has priority.

### Initial State

At startup, the engine resolves the initial stack by recursively following states marked `INITIAL`. If no state is marked initial at a given level, the first defined state is used.

---

## Transition Evaluation

### Evaluation Order

Transitions are evaluated **in definition order** within the `TRANSITIONS` block. The first transition whose condition evaluates to `true` (and is not blocked by NOT LOOPING) fires. **Definition order is therefore a priority mechanism.**

```
TRANSITIONS {
    TRANSITION "drive" -> "drive slow" NOT LOOPING { ... }   // checked first
    TRANSITION "drive" -> "drive fast" NOT LOOPING { ... }   // checked second
    TRANSITION "drive" -> "observe" { }                       // fallback (unconditional)
}
```

### When Transitions Are Checked

Transitions are checked at **two points** during a tick:

1. **After each ENTRY block** — but only if the ENTRY block contained actions. If `onEntry` is empty, the engine skips the transition check and proceeds to DO. This prevents unconditional transitions from firing before DO gets a chance to execute.
2. **After the DO block** of the innermost state.

If a transition fires, the tick ends immediately. The new state's ENTRY will run on the **next** tick.

### Unconditional Transitions

A transition without a `CONDITION` block is parsed as `VALUE("true")` — it fires unconditionally whenever it is evaluated. These are typically used as fallback transitions at the end of the transition list.

### Condition Types

| Type | Syntax | Behavior |
|------|--------|----------|
| **VALUE** | `CONDITION { VALUE("path") }` | Resolves a boolean model property. Literals `"true"` / `"false"` are constants. |
| **EVAL** | `CONDITION { EVAL { ... } }` | Executes Python code; expects a boolean return value. |
| **EVENT** | `CONDITION { EVENT("domain", "name") }` | Checks the event bus for a matching event. If found, the event is **consumed** (marked as processed by this engine). |

> **Side effect**: EVENT conditions consume the event as soon as the condition is evaluated as `true`, even if the transition is later blocked by NOT LOOPING. This is a known trade-off for lazy evaluation.

---

## NOT LOOPING

A transition marked `NOT LOOPING` will **not fire consecutively**. If the exact same transition was the last one fired by this state machine context, it is skipped.

```
TRANSITION "drive" -> "drive fast" NOT LOOPING { ... }
```

**Key semantics:**
- NOT LOOPING compares the transition **object identity**, not just from/to names.
- When a NOT LOOPING transition is skipped, the engine continues evaluating the **next** transition in definition order. This is how fallback transitions (e.g., `drive → observe`) get a chance to fire.
- Once a *different* transition fires, the NOT LOOPING block is reset — the previously blocked transition can fire again on the next evaluation.

**Example cycle:**
```
Tick  9: drive → drive fast   (fires, EVAL=true, NOT LOOPING)
Tick 10: drive → drive fast   (SKIPPED — same as last)
         drive → observe      (fires — unconditional fallback)
Tick 11: observe → drive      (fires — unconditional)
Tick 12: drive → drive fast   (fires again — last was observe→drive)
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

`$(path)` references inside EVAL blocks are resolved **at code-generation time** (before Python execution). The current model values are substituted as Python literals:

```
SET("y", EVAL { return $(boundingBox->position->y) + 0.3 * $(direction->y) })
```

This means `$(boundingBox->position->y)` is replaced by the **current** value of `y` each time the EVAL is executed. Changes from previous ticks are visible.

---

## Event System

### Domains

Events are organized into **domain buckets** (e.g., `"public"`, `"local"`). Domains are created lazily when the first event with a new domain name is published.

### Event Sources

| Source | Origin |
|--------|--------|
| `INTERNAL` | Produced by an `EVENT(...)` action inside a state machine. |
| `EXTERNAL_ENGINE` | Produced by another engine running in a separate thread. |
| `EXTERNAL_MESSAGE` | Arrived from an outside client via WebSocket. |

### Time-To-Live (TTL)

Every event has a TTL. Once `currentTime - event.timestamp > ttlMs`, the event is expired and ignored. Expired events are purged once per tick. Events with `ttlMs = 0` never expire.

### Event Consumption Model

Events are **not deleted** when consumed. Instead, the consuming engine's ID is recorded on the event. This means:

- Multiple engines can independently react to the same event.
- The same engine will **not** react to the same event twice.
- The event dies naturally when its TTL expires.

### Shared Event Bus

All engines within the same environment share a single `EventBus`. Events published by one engine are immediately visible to all others. The bus uses `ConcurrentHashMap` and `CopyOnWriteArrayList` for thread safety.

---

## Threading Model

- Each environment runs its engine on a **dedicated thread**.
- Multiple state machines attached to objects within the same environment are executed **sequentially** within one tick (not in parallel).
- The model is written **only** by the engine thread. Observer callbacks (e.g., WebSocket notifications) are dispatched asynchronously.
- The `EventBus` is the only shared mutable data structure between engine threads and is fully thread-safe.

---

## Error Handling

- If an `onEntry` or `onDo` action block throws an exception, the error is **logged** (via the trace logger and stderr) but the engine **continues**. The tick proceeds normally.
- If a transition target state cannot be found, the transition is **skipped** and an error is logged.
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

