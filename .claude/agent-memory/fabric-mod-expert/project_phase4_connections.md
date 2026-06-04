---
name: Phase 4 connections lifecycle
description: Persistent UUID-keyed bidirectional connection records, resolver, create/load/destroy lifecycle, and the storage-location decision
type: project
---

Phase 4 adds the durable connection graph the roadmap (§7) wants, layered ON TOP of Phase 3's
runtime position-keyed wake (which is unchanged — Phase 4 is additive, it does not replace the wake
path).

**Components (package `com.github.drewchase.connection`):**
- `CofferConnections` (duck on BlockEntity): `Set<UUID> coffer$connections()`, add/remove/set, plus
  a one-time `needsFreshnessCheck` flag. Implemented by `BlockEntityConnectionsMixin`, which persists
  the set in the chunk-region stub via `saveAdditional`/`loadAdditional` TAIL using
  `UUIDUtil.CODEC_SET`. add/remove call `setChanged()` so edges persist; `setConnections` (load
  path) does NOT (avoids spurious dirty on load).
- `EndpointResolver` + `EndpointResolverManager`: server-scoped `Map<UUID,BlockEntity>` live index +
  `exists(uuid)` fallback (live OR side-store file present). Bound per server in `Coffer.onInitialize`.
- `Connections` (static helpers): `connect(a,b)` (bidirectional, assigns UUIDs), `checkFreshness`
  (one-time prune of edges that resolve to neither live nor store), `destroy` (teardown notifying
  live counterparts directly and OFFLINE counterparts via `InventoryStore.removeConnectionEdge`).

**Resolver indexing is driven from `BlockEntityUuidMixin`** (the UUID lifecycle hub), NOT the
connections mixin — registration fires from `coffer$setUuid`/`getOrCreateUuid`/`setLevel` TAIL and
deregistration from `setRemoved` TAIL. This is deliberate: it avoids depending on the order the two
stub mixins' `loadAdditional` TAIL callbacks run.

**Lifecycle hooks:**
- Create on place: `HopperPollingMixin.coffer$ensureRegistered` (first tick after placement, when
  neighbours are loaded) resolves the eject-target and suck-source BEs; if Container, `Connections.
  connect`. Also runs `Connections.checkFreshness` on the hopper's own restored edges first.
- Load freshness: per §7 this is a TRANSFER-entity concern ("check the connected inventory still
  resolves before trusting a cached decision") — so it runs on the hopper's first tick, not on every
  inventory. Inventory-side stale edges are harmless (destroy notifications no-op on them).
- Destroy: `BlockEntityDestroyMixin` injects HEAD of `BlockEntity.preRemoveSideEffects(BlockPos,
  BlockState)` — the chunk calls this ONLY on a real server-side break/replace, NOT on chunk unload
  (verified in LevelChunk: `blockChanged && hasBlockEntity && !keep`). `setRemoved()` fires on BOTH
  unload and break, so it is used only for resolver DEregistration, never for edge teardown.

**STORAGE-LOCATION DECISION:** connection set lives in BOTH the region stub (cheap to read on load,
the runtime copy) AND the side-store envelope (key `coffer:connections`, the offline-editable copy).
On a loaded container save, both are written in sync. Offline edge removal edits only the side-store
envelope (`removeConnectionEdge`); the stale stub copy is reconciled by the freshness prune on the
endpoint's next load. The side-store envelope is now `{coffer:dimension, coffer:contents,
coffer:connections}` and `InventoryStore.write` takes the connection set as a 4th arg.

**Gotchas:** `UUIDUtil.CODEC_SET` is `Codec<Set<UUID>>`; encode/decode raw NBT via
`codec.encodeStart/parse(NbtOps.INSTANCE, ...)`. `preRemoveSideEffects` is the break-only hook.
