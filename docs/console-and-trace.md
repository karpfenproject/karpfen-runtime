# Console and trace log of the runtime

This document shows, for people who write tools that read the output of the runtime, what the runtime prints on its
console and what it writes into a trace log. The options, the types of the trace entries and their details are
described in [3.3 Running the runtime](reference/3.3-running-the-runtime.md).

## Configuration at the start

With the shipped `application.conf` the runtime begins its console output with these lines. The options behind them
are listed in [3.3.4 Configuration](reference/3.3-running-the-runtime.md#334-configuration).

```text
[Main] Configuration loaded:
[Main]   Server: 127.0.0.1:8080
[Main]   WebSocket enabled: true
[Main]   Log level: INFO
[Main]   Engine tracing: false
[Main]   Default tick delay: 50ms
[Main]   Default event TTL: 3000ms
[Main]   Event consumption on fire: true
[Main]   Result caching: true (at most 10000 results and 64 MB per environment)
```

## Trace log

The rules for the trace log, its types and the details of each type are in
[3.3.5 Trace log](reference/3.3-running-the-runtime.md#335-trace-log). Every line below the header has this form.

```text
[HH:mm:ss.SSS] [<envKey>] [<object>] <TYPE>: <message> | <name>=<value>, <name>=<value>
```

### Start of a run

A run of the lamp example project begins its trace log with the header and these lines.

```text
# Engine Trace Log — Engine: env-1763035112345 — Started: 2026-11-13T09:12:01.120Z
[10:12:01.124] [env-1763035112345] [lamp] STATE_MACHINE_ATTACHED: State machine of the run configuration attached | stateMachine=, path=0
[10:12:01.125] [env-1763035112345] [lamp] INITIAL_STATE: Initial state stack: off | path=0, stack=off
[10:12:01.126] [env-1763035112345] [wallSwitch] STATE_MACHINE_ATTACHED: State machine of the run configuration attached | stateMachine=, path=0
[10:12:01.126] [env-1763035112345] [wallSwitch] INITIAL_STATE: Initial state stack: down | path=0, stack=down
[10:12:01.127] [env-1763035112345] [*] ENGINE_START: Engine started with 2 state machine(s), tickDelay=500ms
[10:12:01.128] [env-1763035112345] [lamp] TICK_START: Tick #1 | path=0, stack=off, currentStates=off, parallel=false
[10:12:01.129] [env-1763035112345] [lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'off' | path=0, state=off, stack=off
[10:12:01.131] [env-1763035112345] [lamp] STATE_DO_SKIP: onDo for state 'off' is empty — skipped | path=0, state=off, stack=off
[10:12:01.131] [env-1763035112345] [lamp] TICK_END: Tick #1 completed | path=0, stack=off, currentStates=off, parallel=false
```

### Split and join

A lamp that tests its bulb and its switch in two branches and joins the branches in `on` writes, among others, the
lines below. They are shortened to the object, the type, the message and the details. The split on path 0 starts the
paths 1 and 2, and the join ends both and starts path 3.

```text
[lamp] TRANSITION_FIRED: idle -> off | path=0, from=idle, to=off, stack=off
[lamp] SPLIT_FIRED: off -> test bulb,test switch | path=0, starts=1,2, from=off, to=test bulb,test switch
[lamp] TICK_END: Tick #2 completed | paths=1,2, stack=test bulb,test switch, currentStates=test bulb,test switch, parallel=true
[lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'test bulb' | path=1, state=test bulb, stack=test bulb
[lamp] TRANSITION_FIRED: test switch -> check position | path=2, from=test switch, to=check position, stack=check position
[lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'bulb ok' | path=1, state=bulb ok, stack=bulb ok
[lamp] JOIN_FIRED: bulb ok,switch ok -> on | ends=1,2, path=3, from=bulb ok,switch ok, to=on
[lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'on' | path=3, state=on, stack=on
```

### Exchange

The state machine `glowing` of the bulb runs on path 0 and is exchanged for `blinking`. The exchange takes one line,
and the start stack of the new state machine follows it on path 1.

```text
[bulb] STATE_MACHINE_EXCHANGED: State machine 'glowing' exchanged for 'blinking' | ended=glowing, started=blinking, ends=0, path=1
[bulb] INITIAL_STATE: Initial state stack: off | path=1, stack=off
```
