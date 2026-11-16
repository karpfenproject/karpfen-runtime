# Open points

- The test of `ATTACH` with an attachable state machine that has a container state needs Python and carries the tag
  `python`; it has not run yet.
- Breakpoints, EventRecording and EventInjection with container states have no tests of their own. They work on the
  flattened state machine and its qualified names like on any other.
- `GET /observatory/stateMachines` does not list the fragments of an environment.
