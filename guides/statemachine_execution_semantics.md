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

### Transitions into nested states

The target of a transition decides the new stack: it is the path from the outermost state down to the target, and
nothing below it.

- A transition into a state that has nested states stops at that state. The state is then the innermost one, so its
  `DO` block runs, and none of its nested states is active. Only the stack at startup follows `INITIAL` downwards.
- The `ENTRY` blocks that run are those of the states on the new stack below the part it shares with the old stack.
  A state that stays active is not entered again.
- A transition to the state itself, or to one of its outer states, enters the target again and with it the state
  directly around the target. For a top-level target only the target is entered again.
- There are no exit blocks. Leaving a state runs nothing.
- After an `ENTRY` block with actions, the transitions of every state on the stack are checked, also those of
  nested states whose own `ENTRY` has not run yet. If one fires, the remaining `ENTRY` blocks do not run.

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
- NOT LOOPING only has an effect on a `TRANSITION`. A `SPLIT` is never held back by it, because the transition fired
  last before a split can fire again is the join in between. A `JOIN` is not checked against it at all.

**Example cycle:**
```
Tick  9: drive → drive fast   (fires, EVAL=true, NOT LOOPING)
Tick 10: drive → drive fast   (SKIPPED - same as last)
         drive → observe      (fires - unconditional fallback)
Tick 11: observe → drive      (fires - unconditional)
Tick 12: drive → drive fast   (fires again - last was observe→drive)
```

---

## Parallel States (SPLIT and JOIN)

A single state machine can run several regions at once. This is *simulated* parallelism — everything still happens on the one engine thread, in a fixed order, so it stays deterministic — and it is independent of the genuine multithreading used to run *different* state machines.

### Simple vs. parallel context

At any moment a machine's execution context is either **simple** (one current state stack, the normal case) or **parallel** (several current state stacks, called *branches*). The mode is just the number of branches, so the engine always knows which it is in. You enter parallel mode with a SPLIT and leave it with a JOIN; there is no nesting (you can only split while simple).

### SPLIT and JOIN transitions

```
SPLIT "observe" -> "scanLeft", "scanRight"        { CONDITION { EVENT("public", "go") } }
JOIN  "scanLeft", "scanRight" -> "report"         { }
```

- A **SPLIT** (1-to-N) leaves one state and creates one branch per target. It is only considered while the context is simple.
- A **JOIN** (N-to-1) collapses all branches back into a single state. It is only checked while the context is parallel, and it is checked **before** the branches' own transitions each tick — joins have priority.

### How a parallel tick runs

Each tick, for a parallel context:

1. **Join first.** If a join's source states match the branches (see below) and its condition holds, it fires: every branch is discarded and the context becomes simple again, entering the join target.
2. **Otherwise, tick each branch in order.** Each branch runs its own ENTRY/DO and may independently fire its own 1-to-1 transitions — so more than one branch can advance in the same tick. Branches are completely independent; the machine may even be in the same state in several branches at once. The engine does not enforce any well-formedness here — meaningful use is up to the chart designer.

### When does a join match?

A join fires only when its source states correspond **exactly** to the set of branches: there must be as many branches as source states, and each source state must be active (anywhere in that branch's stack, not only the innermost state) in a *distinct* branch. So every branch has to be accounted for — a join is "all regions have reached their join points", not "some of them have".

### What a split and a join enter

Each branch of a split gets the stack of its target. The `ENTRY` blocks that run in a branch are those below the part
its stack shares with the stack that was split, as for a normal transition. A join enters its whole target stack
afresh, the outer states included, because all branches are discarded.

### Events and scoped events across branches

Each branch reacts to events independently: an event consumed by one branch can still be consumed by another (broadcast). A branch created by a split inherits its parent's event history, and a branch created by a join inherits the union of the joined branches' histories, so a fresh branch never re-handles events its ancestors already handled. Scoped events ([see above](#reading-event-payloads)) and the NOT LOOPING memory are tracked per branch.

An event that fires a split becomes the scoped event of every target, so each branch can read its payload. An event
that fires a join is different in three ways. The guard clauses of the join can read its payload, but it does not
become the scoped event of the target. The join looks at the events of the bus on its own, so it also sees events
that its branches already handled. And the event is marked as handled only for the branch the join creates, so the
same event can fire the same join again if the state machine splits again while the event lives.

---

## Histories, tick limits and breakpoints

A state can carry a history, a tick limit and breakpoints, and a transition can carry breakpoints. They are always
read from the `.kstates` file, but each of them only has an effect in an environment whose feature is switched on:
`History`, `TickLimit` and `Breakpoint`. The [features guide](features.md) tells how to switch a feature on and
describes breakpoints. Without the feature the state machine runs as if the words were not there.

### Histories

```
DEEP HISTORY STATE "operating" {
    STATE "cars go" { }
    STATE "cars wait" { }
}
```

A state with a history is entered again where it was left.

- **Remembering.** At the start of every tick, each active state that has a history and is not the innermost state
  remembers a state. With `SHALLOW HISTORY` it is the state directly nested in it that is active. With `DEEP HISTORY`
  it is the innermost active state.
- **Leading on.** When the state with the history is the innermost state and remembers a state, the state machine
  goes on to the remembered state. This happens after the `ENTRY` blocks of the tick and after the timeout check,
  and it ends the tick. The `ENTRY` blocks on the way to the remembered state run in the next tick.
- A state that was never active with a nested state remembers nothing. Entering it stops at the state itself, as
  described under [Transitions into nested states](#transitions-into-nested-states).
- What a state remembers is kept until the engine starts again. It is not forgotten when the remembered state is
  left in the normal way.

Two consequences are easy to miss. A state that remembers a state never rests as the innermost state, so its `DO`
block does not run any more. And a transition from a nested state back to the state with the history leads straight
on to the remembered state again.

### Tick limits

```
STATE "cars wait" {
    TICK LIMIT {
        DELAY: 2 TICKS
        TIMEOUT: AFTER 10 TICKS TRANSITION TO "cars go"
    }
}
```

The engine counts for every active state the ticks it has been active. The tick in which a transition enters the
state does not count; the next tick is its first. A state that is entered again, also by a transition to itself,
starts counting anew. The initial states count from the first tick.

- **`DELAY: n TICKS`** holds back every transition that would leave the state until the state has been active for
  more than `n` ticks. A transition between states nested in it is not held back, because it does not leave the
  state. A transition that is held back is not replaced by another one: nothing fires in that tick, the event of the
  transition stays unhandled, and the transition is tried again in the next tick. A join is held back by the delay
  of any state in any of its branches.
- **`TIMEOUT: AFTER n TICKS TRANSITION TO "state"`** takes the state machine to the named state once the state has
  been active for more than `n` ticks. The engine checks the timeouts after the `ENTRY` blocks of a tick, before the
  histories and the `DO` block, the innermost state first. A timeout ends the tick like any transition. It is
  applied like a `TRANSITION` without a condition, so a delay of a state it leaves holds it back as well.

With `DELAY: 5 TICKS` the state can be left in its sixth tick at the earliest. With `TIMEOUT: AFTER 5 TICKS` it is
left in its sixth tick at the latest.

In a parallel context every branch checks the timeouts of its own stack, after the joins. The ticks are counted per
state name, so a state that is active in two branches at once has one count.

---

## Action Execution

### Action Types

Operations come in three arities. Every operation has a lowercase alias (`set` == `SET`, etc.).

**Simple-property operations** — operate on primitive scalars / lists only:

| Operation | Arity | Behavior |
|-----------|-------|----------|
| `SET(path, value)` | binary | Overwrites a single **scalar** property. Rejects lists and relations. |
| `APPEND(path, value)` | binary | Appends one element to a **simple list** property. |
| `SETLIST(path, value)` | binary | Overwrites a whole **simple list** property (EVAL/MACRO should return a list). |
| `DROPLIST(path)` | unary | Clears a **simple list** property (sets it empty). |

**Object/relation operations** — operate on `has`/`knows` relations:

| Operation | Arity | Behavior |
|-----------|-------|----------|
| `SETOBJ(source, relation, target)` | ternary | Replaces the target of an **atomic** relation. |
| `APPENDOBJ(source, relation, target)` | ternary | Adds an object to a **list** relation. |
| `DROPOBJ(path)` | unary | Removes an object and every relation pointing to it from the model. |

For the object operations, `source` is a data-access path to the owning object (`"self"` or `""` denotes the
context object) and `relation` is the relation key. The `target` is the right-hand side.

| Operation | Arity | Behavior |
|-----------|-------|----------|
| `EVENT(domain, name)` | binary | Publishes an internal event to the shared event bus. |

### Object-manipulation validation rules (`has` vs `knows`)

Object operations are validated strictly against the metamodel so that the model's containment structure
stays well-formed. The single invariant enforced is:

> Every object is the target of **exactly one `has` (composition) relation** — its one parent; root
> objects have none. `knows` (reference) relations may point at any existing object, any number of times.

This yields two rules, applied uniformly to `SETOBJ`/`APPENDOBJ`/`DROPOBJ`:

- A **`has`** relation target must be a **new** object — produced by an `EVAL`/`MACRO` that builds a fresh
  object (and its embedded subtree). It is embedded here, assigned a fresh id, and registered. Putting an
  already-contained object into a `has` relation is rejected (it would gain a second parent).
- A **`knows`** relation target must be an **existing** object — addressed by a data-access path (`VALUE`)
  or a macro returning `reference("T")`. Only a reference is added/repointed. A brand-new object is rejected
  (it would dangle, with no `has` parent).

`SETOBJ` on an atomic `has` relation drops the previous occupant (cascading like `DROPOBJ`) before embedding
the new one. `DROPOBJ` detaches the object from its `has` parent, scrubs every inbound `knows` reference,
and recursively removes its embedded subtree; each removed object id is reported to observers via an
`objectDeleted` notification.

### WITH … AS binding blocks

A `WITH macro_call AS "name" { ... }` block evaluates the macro **once** and binds its result to `name` for
the body, readable as `$(name->field)` (object result) or `$(name)` (scalar/list). This is how a single
macro evaluation feeds several writes — for example a value-wise update of an existing object's fields,
preserving its id, instead of replacing the whole object:

```
WITH MACRO("opposite_direction_with_variance", "boundingBox->position", "closest_obstacle->boundingBox->position") AS "dir" {
    SET("direction->x", EVAL { return $(dir->x) })
    SET("direction->y", EVAL { return $(dir->y) })
}
```

Without it, two `SET("...->x/y", MACRO(...))` would run the macro twice — inconsistent for nondeterministic
macros and wasteful. Bindings live in the action scope (the same scope mechanism as `event`), are visible to
nested `EVAL`/`MACRO` right-hand sides and macro-call arguments, and blocks may be nested and combined with
`IF IN SCOPE`.

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
- Each tick runs inside a tick gate (`TickGate` in the engine module): the engine holds a fair lock while it ticks
  and sleeps outside it. Model snapshots (`GET /observatory/model`) are read between ticks through
  `Engine.readBetweenTicks`, so a snapshot never sees a half-applied action block and contains every change whose
  message was already queued.
- Each `ENTRY` or `DO` block publishes its changes as one batch when it ends. When an action of the block fails, the
  changes made before the failure are still published with that block; nothing waits for the next block.
- Every event published on the bus, sent by a client or raised by an `EVENT` action, is announced to the domain
  listeners of its domain as a `domainEvent` message. Raised events are announced while their block runs, so before
  that block's object changes.

---

## Error Handling

- If an `onEntry` or `onDo` action block throws an exception, the error is logged (via the trace logger and stderr) but the engine continues. The tick proceeds normally.
- If a change reaches an object whose `knows` relations lead back to it, the object has no JSON: the block's commit
  still publishes its other changes and its deletions, then fails with
  `Reference loop through object '<id>' cannot be written as JSON`, which is logged like an action error. The change
  that cannot be written uses up its revision without a message, so model subscribers see a gap. When an action of the
  block failed as well, the action's error is logged and carries the commit's error as a suppressed exception.
- If a transition target state cannot be found, the transition is skipped and an error is logged.
- If an EVAL or MACRO Python subprocess fails (non-zero exit code), a `RuntimeException` is thrown, caught by the action block error handler, and logged.
- If a VALUE condition path cannot be resolved, the condition evaluates to `false`.
- If a client event's payload cannot be parsed against its type, also when it is nested too deeply to parse, the event
  has no payload object and still fires by name (`EventPayloadParser`).
- If an exception or error ends the environment's event loop, which hands client events to the bus, the engine is
  stopped too and the model snapshot reports the phase `halted` (`EnvironmentThread.phase`).

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
| `TRANSITION_START` | A transition is about to be applied. A breakpoint at its start halts after this entry. |
| `TRANSITION_FIRED` | A transition was applied (includes from, to, condition type, stacks). |
| `SPLIT_START / SPLIT_FIRED` | The same two entries for a split. |
| `JOIN_START / JOIN_FIRED` | The same two entries for a join. |
| `TRANSITION_SKIPPED_LOOP` | A NOT LOOPING transition was blocked. |
| `ACTION_ERROR / ENGINE_ERROR` | Errors during execution. |

Traces can be written to a log file for post-mortem analysis.

