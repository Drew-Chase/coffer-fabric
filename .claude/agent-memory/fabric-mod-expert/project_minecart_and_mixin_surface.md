---
name: Hopper minecart integration + mixin surface verification
description: Container minecart entity-path storage integration, and the verified full mixin target surface for MC 26.1.2
type: project
---

**Hopper minecart integration.** Container minecarts are ENTITIES, not block entities, so the
block-entity storage mixins never touched them. `MinecartContainerStorageMixin` (on
`AbstractMinecartContainer`, covering chest + hopper minecarts) brings them into the lazy-storage
model via the entity serialization path, reusing `InventoryStore` + `CofferInventory`:
- Identity = the entity's own `getUUID()` (no stub UUID; vanilla persists entity UUIDs). Implements
  `CofferInventory` only — NOT `CofferUuidHolder`/`CofferConnections`.
- Divert at TAIL of `AbstractMinecartContainer.addAdditionalSaveData` — that method writes `"Items"`
  itself (via the `ContainerEntity.addChestVehicleSaveData` default), so its TAIL genuinely runs
  after items are written (no early-fire bug like the block path had). Store capture re-serializes
  via `addChestVehicleSaveData` (Items-only); hydrate via `readChestVehicleSaveData(ValueInput)`.
- Defer at TAIL of `readAdditionalSaveData` (skips the hydrate pass via `coffer$isHydrating()`).
- Load triggers on `getItem/setItem/removeItem/removeItemNoUpdate`; orphan-GC store delete at TAIL of
  `remove(RemovalReason)` when `reason.shouldDestroy()`.
- Comparator cache reused for free: `applyNaturalSlowdown` reads `getRedstoneSignalFromContainer(this)`
  every movement tick → the comparator mixin returns the cached signal while unloaded.
- DELIBERATELY EXCLUDED: connections (minecarts move, no fixed endpoints) and transfer dormancy. The
  minecart's `suckInItems()` calls `HopperBlockEntity.suckInItems` — a DIFFERENT static method than
  the block hopper's `pushItemsTick` that Phase 3 gates — so minecart polling stays vanilla.
- Cross-interaction already worked pre-integration: a hopper minecart pulling from a Coffer block
  chest triggers that chest's lazy load through the existing `getItem` trigger.

**Mixin surface verification (MC 26.1.2).** All targets confirmed via genSources; enforcement is
config-wide `defaultRequire: 1` (every injector must match >=1 or boot fails) + `requireAnnotations:
true`. Overloaded targets use full descriptors. The headless `./gradlew runServer` boot is the
injection test to re-run on every version bump (it has already caught real failures: a bad
`@Shadow` of an inherited field, and the descriptor-disambiguation needs).

11 mixins / targets (all Mojang names, non-obf):
- AbstractContainerMenuComparatorMixin -> AbstractContainerMenu.getRedstoneSignalFromContainer (static, HEAD cancellable)
- BlockEntityUuidMixin -> BlockEntity.saveAdditional/loadAdditional/setLevel/setRemoved (shadows level)
- BlockEntityConnectionsMixin -> BlockEntity.saveAdditional/loadAdditional (shadows setChanged)
- BlockEntityStubStorageMixin -> BlockEntity.saveWithFullMetadata(ValueOutput)[descriptor]/loadAdditional (shadows saveWithoutMetadata, level)
- BlockEntityLazyLoadMixin -> BlockEntity (interface impl; shadows loadCustomOnly, level; no injects)
- BlockEntityDestroyMixin -> BlockEntity.preRemoveSideEffects
- MarkDirtyMixin -> BlockEntity.setChanged(Level,BlockPos,BlockState)[descriptor] (static)
- ContainerAccessLoadTriggerMixin -> BaseContainerBlockEntity.getItem/isEmpty/removeItem/removeItemNoUpdate/setItem/clearContent
- ContainerAccessRecordMixin -> BaseContainerBlockEntity.setItem/removeItem/removeItemNoUpdate + saveAdditional/loadAdditional (shadows getItem; CANNOT shadow inherited `level` — use getLevel())
- HopperPollingMixin -> HopperBlockEntity.pushItemsTick (HEAD cancellable + RETURN; shadows cooldownTime)
- MinecartContainerStorageMixin -> AbstractMinecartContainer.addAdditionalSaveData/readAdditionalSaveData/getItem/setItem/removeItem/removeItemNoUpdate/remove

All HEAD/TAIL/RETURN injects on whole methods (no INVOKE/FIELD @At), so require=1 is correct for every
one — none should match a variable number of call sites.
