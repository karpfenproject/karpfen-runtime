# Open points

- Breakpoints, EventRecording and EventInjection with container states have no tests of their own. They work on the
  flattened state machine and its qualified names like on any other.
- `GET /observatory/stateMachines` does not list the fragments of an environment.
- The Python processes of an environment and the loop they run have not been tried on Windows.
- The ticks of a split and join cycle and of an event every tick grow longer the longer the engine runs; see
  `GrowthBenchmark`.
- The notebook `compare-benchmark-runs.ipynb` has not been run.
