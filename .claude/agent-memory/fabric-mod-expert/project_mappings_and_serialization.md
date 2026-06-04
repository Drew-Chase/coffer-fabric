---
name: MC 26.1.2 mappings + serialization model (resolved)
description: Resolves the two roadmap unknowns — mappings source and the block-entity serialization API for Minecraft 26.1.2
type: project
---

Two roadmap blockers for Coffer (MC 26.1.2 / Loom 1.16.3) are now resolved by reading genSources output.

**Mappings:** MC 26.x ships **non-obfuscated** — the game already uses official Mojang names at runtime. Loom requires **NO `mappings` dependency** in build.gradle. Adding `loom.officialMojangMappings()` FAILS with "Cannot use Mojang mappings in a non-obfuscated environment". The fabric-example-mod `26.1.2` branch confirms: the dependencies block has no mappings line. All mixin targets use **Mojang names** (e.g. `BlockEntity`, `saveAdditional`, `loadAdditional`, `worldPosition`).

**Why:** unblocks all mixin work; Yarn is not published for 26.x.
**How to apply:** never add a `mappings` line for 26.x; use Mojang names in every `@Inject`/`@At` target. If a build error says "Cannot use Mojang mappings in a non-obfuscated environment", delete the mappings line.

**Serialization model:** block entities persist via `ValueInput`/`ValueOutput` (NOT raw `CompoundTag`). Key hooks on `net.minecraft.world.level.block.entity.BlockEntity`:
- `protected void saveAdditional(ValueOutput output)` — subclass content write hook.
- `protected void loadAdditional(ValueInput input)` — subclass content read hook.
- `ValueOutput`: `<T> void store(String, Codec<T>, T)`, `putInt/putString/...`, `ValueOutput child(String)`.
- `ValueInput`: `<T> Optional<T> read(String, Codec<T>)`, `getStringOr`, `Optional<ValueInput> child(String)`.
- UUID round-trips via `net.minecraft.core.UUIDUtil.CODEC` (a `Codec<UUID>`).
- World save root: `MinecraftServer.getWorldPath(LevelResource.ROOT)`. `LevelResource.ROOT = new LevelResource(".")`.
- `BlockEntity` exposes `protected final BlockPos worldPosition` and `getType()` — enough for a `{UUID, BlockPos}` stub.

**How to apply:** Phase 1+ storage mixins inject at `TAIL` of `saveAdditional`/`loadAdditional` on `BlockEntity`. The §8 plan rows that said "write/writeNbt or ReadView/WriteView" resolve to `saveAdditional`/`loadAdditional` + `ValueOutput`/`ValueInput`.
