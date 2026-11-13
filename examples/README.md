# Examples of the karpfen runtime

Each folder holds one example: a domain metamodel (`*.kmeta`), a model (`*.kmodel`), a state machine
(`*.kstates`) and the event definitions with their payloads (`EVENTS.kmeta`). Every folder has its own `README.md`
with the steps to run it.

How the runtime runs these examples is in
[3.1 Execution semantics](../docs/reference/3.1-execution-semantics.md), and how to run them is in
[3.6 Examples](../docs/reference/3.6-examples.md).

| Example | Domain | Feature focus |
|---------|--------|---------------|
| [single_robot_example](single_robot_example/) | Cleaning robot | Nested states, `EVAL` and `MACRO` in actions, `WITH ... AS`. |
| [delivery_drone_example](delivery_drone_example/) | Delivery drone | Event payloads and scoped events: `EVENT` conditions on payload fields, reading `$(event->...)`, `IF IN SCOPE`. |
| [autonomous_car_example](autonomous_car_example/) | Self-driving car | Parallel states: a `SPLIT` into self-test branches and a `JOIN` back once all of them are done. |
| [drone_swarm_example](drone_swarm_example/) | Drone swarm | Actions that change objects and lists: `SETOBJ`, `APPENDOBJ`, `DROPOBJ`, `SETLIST` and `DROPLIST` on a fleet that grows and shrinks at run time. |

All four come from cyber-physical systems: robots, drones and cars.
