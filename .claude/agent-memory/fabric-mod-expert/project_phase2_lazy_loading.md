---
name: Phase 2 lazy-loading design decisions
description: How Coffer defers container contents off the region NBT and the 3 architectural decisions (store layout, comparator, threading) that later phases must respect
type: project
---

Phase 2 makes container block-entity contents lazy: the chunk-region NBT holds only a stub, full
contents live in the side store and load on first interaction.

**Mechanism (all on `BlockEntity` / `BaseContainerBlockEntity` mixins, server-side only):**
- Save: vanilla subclass `saveAdditional` writes items under the `"Items"` key; a TAIL inject on
  `BlockEntity.saveAdditional` calls `output.discard("Items")` to strip them from the region, then
  mirrors the full `saveWithoutMetadata` capture to the side store (reentrancy-guarded against the
  inner `saveAdditional`).
- Load: TAIL inject on `BlockEntity.loadAdditional` does NOT read items — it marks the BE unloaded
  (`CofferInventory#coffer$markUnloaded`) and restores only the cached signal.
- Trigger: `coffer$ensureLoaded()` hydrates via `BlockEntity.loadCustomOnly(TagValueInput)`. It is
  invoked from HEAD injects on the concrete `BaseContainerBlockEntity` accessors (`getItem`,
  `isEmpty`, `removeItem`, `removeItemNoUpdate`, `setItem`, `clearContent`) — every GUI/hopper path
  funnels through these. Loaded flag is set BEFORE hydrating to avoid re-entrant re-marking.

**Decision 1 — store layout:** keep sharded `inventories/<2hex>/<uuid>.dat`. Dimension is embedded
INSIDE the file (envelope `{coffer:dimension, coffer:contents}`), not per-dimension dirs, to keep a
single global UUID->endpoint resolver simple.

**Decision 2 — comparator/redstone:** cache a fullness signal (0-15) in the stub
(`coffer:signal`), computed at save time via `AbstractContainerMenu.getRedstoneSignalFromContainer`.
A HEAD/cancellable inject on that SAME static method returns the cached signal for an unloaded
`CofferInventory`, so comparators never force a load. This is why the comparator path is
deliberately excluded from the `getItems` load triggers.

**Decision 3 — threading:** synchronous load on first touch. Async/deferred-open punted to Phase 6.

**CRITICAL for later phases:**
- Saving an UNLOADED container must NOT rewrite the store (its in-memory items are empty
  placeholders) — that branch only re-emits the cached signal. Any future code that mutates an
  unloaded container must load it first or it will lose data on save.
- The cached `coffer:signal` is only refreshed when a LOADED container saves. Phase 3's dirty-event
  / transfer work mutates contents while loaded, so the signal stays correct — but if a future path
  mutates contents without a subsequent loaded-save, the comparator cache goes stale.
- `ResourceKey<Level>` value accessor in MC 26.1.2 is `.identifier()` (returns `Identifier`), NOT
  `.location()`.
