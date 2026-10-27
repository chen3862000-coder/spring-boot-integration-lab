# AI Coding Guidelines

## Code Documentation

- Document public APIs and key classes when their purpose or behavior is not self-evident. Use class-level comments to explain a class's responsibility and method-level comments to describe its contract, not its implementation line by line.
- For HTTP APIs, document required inputs, validation constraints, idempotency/replay behavior, important response and error statuses, and whether reads have side effects or trigger reconciliation.
- For domain and integration code, document non-obvious invariants and failure semantics, especially state transitions, concurrency/ownership guarantees, retry eligibility, ambiguous external outcomes, and persistence or durability limits.
- Keep comments concise, factual, and aligned with current code. Update them when behavior changes; do not turn assumptions or recommendations into claims about implemented behavior.
- Avoid comments that merely repeat names, annotations, or straightforward code. Prefer documenting externally observable contracts and non-obvious reasons; private helpers, simple getters, and routine mappings generally do not need comments.
