# Open points

- Breakpoints, EventRecording and EventInjection with container states have no tests of their own. They work on the
  flattened state machine and its qualified names like on any other.
- `GET /observatory/stateMachines` does not list the fragments of an environment.
- The Python processes of an environment and the loop they run have not been tried on Windows.
- The queue of outgoing WebSocket messages has no bound. One thread sends all messages and waits for each send, so a
  client that reads slower than the environment produces fills the queue for all clients until memory runs out.
  Bounding it means dropping or merging messages, for example keeping only the newest value of an object.
- Every change of an object in an action block is written as JSON with everything reachable from the object, also
  when no client observes the object. The cost grows with the size of that part of the model, not with the run.
- Events with a time to live of 0 stay in memory until the environment is dropped.
- An injected event keeps the timestamp of its upload. With a time to live above 0 it counts as expired on arrival
  when it is injected later than that time after the upload, and no state machine sees it.
- Path numbers are `Int` per object. A state machine that splits and joins on one object every few ticks runs out of
  them after about a month at 1000 ticks per second; the numbers then turn negative and later repeat.
- The notebook `compare-benchmark-runs.ipynb` has not been run.
- A list for EventInjection that cannot be read, also one that EventReplayController uploads, is answered with status
  500, because the parse error is no `IllegalArgumentException`. Status 400 would be right.
- When the runtime ends, only clients of environments that were run get the close with code 1001 and the reason
  `Runtime stopped`. Clients of an environment that was never run just lose their connection.
