---
name: Phase 5 access metadata + Phase 6 hardening
description: Per-inventory access record, orphan GC, and the two Phase 2 save/load divert bugs fixed under hardening
type: project
---

**Phase 5 — Access metadata.** `CofferAccessRecord` (package `access`) holds one record per
container: `{accessTime (game time), accessSlot, accessType}` where `AccessType ∈
{INSERTED_INTO_EMPTY, SLOT_EMPTIED, QUANTITY_CHANGED}`. Implemented by `ContainerAccessRecordMixin`
on `BaseContainerBlockEntity`: HEAD injects on `setItem`/`removeItem`/`removeItemNoUpdate` classify
the change from the pre-mutation stack (ensureLoaded first so it sees real contents) and persist the
record in the region stub. Audit-only — does not drive transfers/events.

**GOTCHA (cost me a failed boot):** a mixin targeting `BaseContainerBlockEntity` CANNOT `@Shadow` the
`level` field — `level` is declared in `BlockEntity` (superclass) and Mixin only locates fields
declared in the target class itself. Use `((BlockEntity)(Object)this).getLevel()` instead. (Mixins
targeting `BlockEntity` directly can shadow `level` fine.)

**Phase 6 — Hardening. Two real Phase 2 bugs found and fixed (save/load consistency):**
1. **Divert hook fired too early.** The Items-strip was injected at `BlockEntity.saveAdditional`
   TAIL, but a container subclass calls `super.saveAdditional()` FIRST and writes `"Items"` AFTER —
   so the strip ran before Items existed and was a no-op (Items stayed in the region; laziness
   defeated, though contents were not lost). FIX: hook the TAIL of `saveWithFullMetadata(ValueOutput)`
   (descriptor-disambiguated) — the chunk persistence entry point (`LevelChunk` calls
   `saveWithFullMetadata(registries)`), which runs after the full polymorphic `saveAdditional`. The
   capture for the store uses `saveWithoutMetadata` (a different method) so no reentrancy guard is
   needed. `saveWithoutMetadata`/`saveWithFullMetadata` are persistence-only (chest `getUpdateTag`
   returns empty), and `saveWithId` (item-drop path) is deliberately NOT hooked.
2. **Hydrate re-marked unloaded.** `ensureLoaded -> loadCustomOnly -> loadAdditional` re-runs the
   deferred-load hook, which called `markUnloaded()` and reset the cached signal mid-hydrate. FIX:
   added `CofferInventory#coffer$isHydrating()` and the deferred hook returns early while hydrating.

**Orphan-file GC:** `InventoryStore.delete(uuid)`, called from `Connections.destroy` (which runs at
`preRemoveSideEffects` TAIL — AFTER vanilla `dropContents` loads+spawns the items, so deleting the
store file does not drop an empty inventory).

**Hardening items addressed by analysis (not code):**
- Multiplayer sync: lazy load completes before slot sync because the menu-open path reads `getItem`
  (-> ensureLoaded) before slots are populated/synced. Handled by Phase 2 design.
- Vanilla timing parity: by design — Phase 3 only gates idle scans; active hoppers keep vanilla's
  cooldown(8) cadence.
- Save consistency two-source-of-truth: missing store file -> ensureLoaded reads null -> empty
  (no crash); broken-endpoint orphan -> deleted on break; stale stub edge -> freshness prune on load.
- Mod-compat / hopper-minecart / crash-mid-save: still need in-game verification (the separate
  Testing cards). Hopper minecarts use the entity path, not block-entity — untouched by this work.
