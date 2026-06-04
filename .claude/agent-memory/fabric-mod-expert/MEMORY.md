# Agent Memory Index

- [Loom 1.16+ no longer provides modImplementation](project_loom_no_modimplementation.md) — use `implementation` for all mod deps in Loom 1.16-SNAPSHOT / MC 26.x
- [Coffer inventory & transfer redesign](project_inventory_redesign.md) — lazy inventory loading + event-driven transfers; high-risk mixin-heavy storage rework
- [MC 26.1.2 mappings + serialization (resolved)](project_mappings_and_serialization.md) — no mappings dep (non-obf, Mojang names); BlockEntity uses ValueInput/ValueOutput + saveAdditional/loadAdditional
- [Phase 2 lazy-loading design](project_phase2_lazy_loading.md) — discard "Items" from region, hydrate on getItems access; cached comparator signal; dimension in store envelope
- [Phase 3 dirty-events + transfers](project_phase3_transfers.md) — gate hopper pushItemsTick by dormancy; setChanged emits dirty; runtime position-keyed TransferNetwork
- [Phase 4 connections lifecycle](project_phase4_connections.md) — persistent UUID connection records (stub+store), EndpointResolver, create-on-place/freshness/destroy; preRemoveSideEffects is break-only hook
