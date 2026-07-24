# Karpfen Runtime — Examples

Each folder is a self-contained scenario: a domain metamodel (`*.kmeta`), a model instance (`*.kmodel`),
a state machine (`*.kstates`), and — where the scenario uses events with structured data — event payload
definitions (`EVENTS.kmeta`). Every folder has its own `README.md` with run instructions.

For the DSL and engine semantics these examples rely on, see
[`../guides/statemachine_execution_semantics.md`](../guides/statemachine_execution_semantics.md).

| Example | Domain | Feature focus |
|---------|--------|---------------|
| [single_robot_example](single_robot_example/) | Cleaning robot | The original end-to-end walkthrough: hierarchical states, EVAL/MACRO actions, `WITH ... AS`. |
| [delivery_drone_example](delivery_drone_example/) | Delivery drone | **Event payloads & scoped events** — `EVENT` guards on payload fields, reading `$(event->...)`, `IF IN SCOPE`. |
| [autonomous_car_example](autonomous_car_example/) | Self-driving car | **Parallel states** — `SPLIT` into concurrent self-test branches and `JOIN` back once all complete. |
| [drone_swarm_example](drone_swarm_example/) | Drone swarm | **Model manipulators** — `SETOBJ` / `APPENDOBJ` / `DROPOBJ` / `SETLIST` / `DROPLIST` on a fleet that grows and shrinks at runtime. |

All four use the cyber-physical domain (robots, drones, cars) so the behaviors stay intuitive while the
DSL features are the real subject.
