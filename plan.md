# Coffer — Inventory & Transfer Performance Redesign

## 0. Context

- **Target:** Minecraft 26.1.2 / Fabric Loader 0.19.3 / Fabric API 0.150.0+26.1.2 / Loom 1.16-SNAPSHOT / Java 25.
- **Mappings:** UNCONFIRMED — `build.gradle` has no `mappings` dependency. Resolve this first.
  All class/method names below are *behavioral descriptions*; confirm exact names with
  `./gradlew genSources` and reading the decompiled sources.
- **Starting point:** fresh example-mod scaffold. This is a greenfield build, not a refactor.
- **Serialization caveat:** modern MC persists block entities via a `ReadView`/`WriteView` +
  `Codec` model, not raw `NbtCompound`. Confirm the exact mechanism for 26.1.2 before touching
  the storage layer — it dictates the entire mixin surface in §8.

## 1. Goals

1. **Lazy inventory loading** — region/chunk holds only a lightweight stub; full block-entity +
   item data lives in `world/inventories/<uuid>` and loads only on interaction.
2. **Event-driven transfers** — transfer entities (hoppers, droppers, hopper minecarts) react to
   inventory "dirty" events and cache their decision, replacing per-tick polling.

## 2. Domain model

- **Inventory (passive endpoint):** anything pushable/pullable — chest, barrel, furnace, dropper,
  hopper. Has a stable **UUID** independent of position/chunk.
- **Transfer entity (active endpoint):** anything that moves items — hopper, dropper, hopper
  minecart. Also has a UUID. Note: a hopper is *both* an inventory and a transfer entity; the model
  must let one object hold both roles.
- **Stub (in region file):** `{ UUID, BlockPos, rotation }` only.
    - `rotation` = render orientation, always present.
    - `logicalFacing` = transfer direction; `null` for passive inventories (rotation has no bearing on
      logic), non-null for transfer entities (used to confirm it points at its target).
- **Contents (in `world/inventories/<uuid>`):** full block-entity NBT + item stacks. Loaded lazily.
- **Access record (one per inventory, audit only, independent of the event system):**
  `{ lastAccessTime, changedSlotIndex, actionType }` where actionType ∈
  { INSERTED_INTO_EMPTY, SLOT_EMPTIED, QUANTITY_CHANGED }.
- **Connection record (bidirectional edge):** each endpoint persists the other's UUID with itself.

## 3. Storage layer

- **UUID identity for block entities.** Block entities are normally keyed by `BlockPos`, not UUID.
  Attach a UUID via a mixin duck-interface on the block-entity class (`coffer$getUuid()` /
  `coffer$setUuid()`), assigned on first creation and persisted in the stub.
    - Hopper minecarts are *entities* (`HopperMinecartEntity`), which already have entity UUIDs and use
      a *separate* serialization path (entity storage, not chunk block-entity NBT). Treat their storage
      integration as a distinct sub-task — do not assume the block-entity path covers them.
- **`world/inventories/` store.** A UUID-keyed on-disk store under the level save directory
  (`LevelStorage.Session` / server save path).
    - Decide flat-vs-sharded layout (a flat dir of millions of files is a filesystem problem; consider
      `inventories/<first-2-hex>/<uuid>.dat` sharding).
    - Decide dimension handling: UUIDs are global, but inventories belong to a dimension. Either store
      per-dimension or record the dimension key inside the file.
- **Stub persistence.** Intercept block-entity serialization so chunk NBT receives only the stub.
  The full data is written to the store instead.

## 4. Lazy loading

- **Stub loads with the chunk** (cheap, always).
- **Contents load on first interaction:** player opens the container, a transfer entity rescans it,
  or a connection's initial scan fires.
- **Load triggers (the natural hooks):**
    - Opening a `ScreenHandler` (player GUI) — load before the handler is built so slot sync works.
    - A transfer entity's rescan touching the inventory.
    - A connection freshness check on load.
- **THREADING:** interaction runs on the server thread; synchronous disk reads cause hitches. Decide:
  accept a one-time synchronous load on first touch, or async-load with a deferred-open. Start
  synchronous; optimize later.
- **VANILLA-COMPAT TENSION (must resolve):** a redstone **comparator** reads container fullness
  every tick via the `Container`/`Inventory` count API. If contents aren't loaded, the comparator
  reads wrong. Either (a) comparator access forces a load (defeats laziness near redstone), or
  (b) cache a lightweight "fullness" summary in the stub. Pick one and document it.

## 5. Dirty-event system

- **Mark-dirty on every mutation path.** Hook the inventory's mutation surface (set-stack /
  mark-dirty) so that *all* content changes — vanilla hopper inserts, player edits, furnace results,
  dispenser fires — flow through one dirty signal. Missing a path means stale caches.
- **Dumb event, no payload.** Dirty == "rescan". (Design decision: a filtered event is more
  efficient but harder to reason about; full rescan is acceptable because entities are idle between
  events.)
- **Early-out:** if the inventory has no connections, marking dirty does nothing — skip the emit.
- **Fan-out:** emit to every connected transfer entity by resolving its UUID.

## 6. Transfer rescan & decision cache

- **Replace polling.** Disable/override the vanilla hopper tick (the periodic insert/extract logic)
  so transfer entities do nothing per-tick by default.
- **On dirty event:** the transfer entity rescans the inventory and caches a decision:
    - `CAN_ACT` → perform the transfer on the next tick.
    - `BLOCKED` → cache the negative and go fully dormant until the next dirty event (e.g., a pusher
      facing a chest full of cobblestone). Cache negatives too — this is the whole point.
- **Initial scan.** On connection create *and* on load, run one scan so pre-existing contents are
  handled without waiting for a future mutation.
- **Feedback loop.** A successful transfer mutates the inventory → marks dirty → re-emits → rescan.
  Guard against infinite same-tick loops (cooldown / once-per-tick action gate, mirroring vanilla
  hopper cooldown semantics for redstone/timing parity).

## 7. Connections (foreign-key-with-edge-cascade)

- **Create:** when a transfer entity is placed in a connecting position (its `logicalFacing` points
  at an inventory), create a **bidirectional** record — each endpoint stores the other's UUID,
  persisted with itself. Trigger an initial scan.
- **Load:** on load from filesystem, run a **single freshness check** that the connected inventory's
  UUID still resolves to a valid endpoint before trusting any cached decision.
- **Destroy:** when an endpoint is destroyed, send a "destroyed" message to its counterpart via the
  stored UUID; the counterpart drops *its* connection record (the edge), not the object.
    - **Offline counterpart:** if the counterpart's chunk is unloaded, the destroy notification must
      mutate the on-disk record in `world/inventories/`, not a live object. Plan for store-level edge
      removal.
- **Resolver/manager:** a UUID→endpoint registry that can resolve to a live object or load it from
  the store. Central to events, connections, and freshness checks.

## 8. Mixin surface (verify all names via genSources)

| Purpose                           | Likely target (behavioral)                                       | Injection                                        |
|-----------------------------------|------------------------------------------------------------------|--------------------------------------------------|
| Attach UUID to block entities     | `BlockEntity`                                                    | duck-interface via `implements` + fields         |
| Write stub only / divert contents | block-entity `write`/`writeNbt` (or `ReadView`/`WriteView` path) | `@Inject` / `@Redirect`                          |
| Read stub, defer contents         | block-entity `read`/`readNbt`                                    | `@Inject`                                        |
| Fire dirty on mutation            | inventory `setStack` / `markDirty`                               | `@Inject` at `TAIL`                              |
| Disable vanilla hopper polling    | `HopperBlockEntity` server tick / `insertAndExtract`             | `@Inject` cancel or `@Overwrite` (prefer Inject) |
| Connection create on place        | `Block#onPlaced` (or BE init)                                    | `@Inject`                                        |
| Connection destroy on break       | `Block#onStateReplaced` / break path                             | `@Inject`                                        |
| Lazy-load on GUI open             | screen-handler open / `use` path                                 | `@Inject`                                        |
| Flush store on world save         | world/chunk save path                                            | `@Inject`                                        |

- Prefer `@Inject` + `CallbackInfo(Returnable)` cancellation over `@Overwrite` for hopper logic to
  preserve mod compatibility.
- Watch `@At` selector drift across versions — `INVOKE`/`FIELD` targets silently fail to apply if the
  bytecode shifts. Use `require`/`expect` and test injection on every version bump.
- Keep all of this server-side; guard any rendering-only rotation code with `@Environment(CLIENT)`.

## 9. Phases / milestones

- **Phase 0 — Scaffold cleanup.** Remove example mixin, set up package structure, mod metadata,
  confirm mappings, run `genSources`, read the real serialization classes.
- **Phase 1 — Identity + store (eager).** UUID on block entities; `world/inventories/` store; write
  stub to region, full data to store; **load eagerly first** to validate the round-trip end-to-end.
- **Phase 2 — Lazy loading.** Defer contents load to interaction triggers; resolve the comparator/
  redstone tension; decide threading.
- **Phase 3 — Dirty events + transfer rescan.** Mark-dirty on all mutation paths; disable hopper
  polling; rescan + decision cache (incl. negative caching); feedback-loop guard.
- **Phase 4 — Connections lifecycle.** Bidirectional records, create/load/destroy, offline-counterpart
  edge removal, UUID resolver.
- **Phase 5 — Access metadata.** Single most-recent access record per inventory.
- **Phase 6 — Hardening.** Crash/save consistency, orphan-file GC, multiplayer sync, vanilla timing
  parity, mod-compat audit.

## 10. Risks & open questions

- **Save consistency:** two sources of truth (region stub + store). Define write ordering and a
  recovery path for a stub whose store file is missing (and vice versa). Need an orphan-GC pass.
- **Comparators / redstone:** see §4 — laziness fights any tick-rate fullness reader.
- **Vanilla timing parity:** event-driven transfers change hopper cadence; verify redstone contraptions
  and item-sorter behavior still work, or document intended divergence.
- **Hopper minecart:** entity storage path ≠ block-entity path; separate integration.
- **Mod compatibility:** other mods that read `Inventory` directly (storage mods, pipes) assume eager
  contents and per-tick semantics. Audit and document breakage.
- **Multiplayer sync:** ensure lazy-load completes before slot sync to clients on GUI open.
- **Mappings unconfirmed:** resolve Yarn vs Mojmap before writing any mixin.

## 11. Testing

- Round-trip: place chest, add items, save/quit, reload → contents intact, stub-only in region NBT.
- Lazy: confirm contents file isn't read until first interaction (log/trace the load).
- Events: hopper goes dormant against a full chest; wakes on the next dirty event.
- Connections: place/break both endpoints incl. with counterpart chunk unloaded.
- Crash safety: kill the process mid-save; verify no orphaned/dangling data on restart.
- Parity: a vanilla item sorter built on hoppers still sorts correctly.
