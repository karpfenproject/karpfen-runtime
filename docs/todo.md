# Open points

- Breakpoints, EventRecording and EventInjection with container states have no tests of their own. They work on the
  flattened state machine and its qualified names like on any other.
- `GET /observatory/stateMachines` does not list the fragments of an environment.
- Code with `import` in an `EVAL` body starts a new Python process for every call; only code that calls a macro with
  `@name` or is longer than 8000 characters runs in the process that stays open.
- What code prints with `print` becomes part of its result, since the runtime reads the result from stdout.
- An `EVAL` that the runtime computes itself for a `string` property gives the number in Kotlin's form, `4.0` where
  Python gives `4`.
- An error in an `EVAL` of a condition goes to the console only, not to the trace log.
- The process that stays open runs every call in the same globals, so a name one call defines can hide one of the
  runtime. Code that writes to file descriptor 1 directly, past `sys.stdout`, breaks the answer of the call.
- A call of a new process that does not end is not ended when its environment is dropped.
- Line breaks in the results of a new process are read with the line separator of the system, which has not been tried
  on Windows.
