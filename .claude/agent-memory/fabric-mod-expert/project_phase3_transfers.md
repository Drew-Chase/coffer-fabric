---
name: Phase 3 dirty-events + event-driven transfers
description: How Coffer replaces per-tick hopper polling with a dormancy-cached, dirty-event-driven model, and the runtime registry that Phase 4 must replace
type: project
---

Phase 3 makes hoppers event-driven instead of polling every tick.

**Mechanism (reuses vanilla transfer logic, only gates it):**
- `HopperPollingMixin` on `HopperBlockEntity`:
  - HEAD inject on static `pushItemsTick` (cancellable): lazily registers the hopper's endpoints,
    then cancels the whole tick if dormant — dormant hoppers do zero scan work.
  - RETURN inject on `pushItemsTick`: if `cooldownTime <= 0` after the tick (a successful move sets
    cooldown 8), the hopper attempted a move and nothing happened -> cache BLOCKED (`coffer$sleep`,
    sets dormant). Active hoppers keep vanilla's exact 8-tick cadence = timing parity.
  - `cooldownTime` is `@Shadow`-ed; vanilla's `setCooldown(8)` is the feedback-loop guard (waking
    only sets a flag; the move is still rate-limited).
- `MarkDirtyMixin` on `BlockEntity`: HEAD inject on the STATIC
  `setChanged(Level,BlockPos,BlockState)` overload (the universal mutation funnel that instance
  `setChanged()` delegates to). Disambiguated by full descriptor because `setChanged` is overloaded.
  Server-side only; emits one dirty signal per mutation to the network.

**CofferTransfer interface** (boundary for the static tick to reach instance state):
`coffer$wake()`, `coffer$beginTransferTick(level,pos,state) -> boolean (skip?)`,
`coffer$endTransferTick()`.

**KEY DECISION — runtime position-keyed network (Phase 4 will replace it):** §5/§6 reference
"connections" (persistent UUID-keyed bidirectional records) which are Phase 4. For Phase 3,
`TransferNetwork` is an in-memory `Map<ResourceKey<Level>, Map<Long packedPos, Set<BlockPos>>>`
mapping endpoint position -> interested hopper positions. A hopper registers THREE endpoints:
eject target (`pos.relative(facing)`), suck source (`pos.above()`), and SELF (`pos`, so a player
inserting into the hopper wakes it to eject). `markDirty(level,pos)` is an O(1) early-out lookup
(honors §5 "skip emit if no connections") that wakes registered hoppers and lazily prunes stale
edges. Bound/cleared per server via `TransferNetworkManager` in `Coffer.onInitialize`.

**Invariants / gotchas for later phases:**
- Hoppers start awake (dormant=false), so pre-existing contents are handled on first load tick
  (the §6 "initial scan").
- Registration is lazy (first tick) and idempotent; pruning is lazy (on markDirty when the target
  is no longer a CofferTransfer). NOTE: Phase 4 added the durable resolver + UUID connection records
  ADDITIVELY (it did NOT replace TransferNetwork); the position-keyed runtime wake still drives
  actual wakeups. Do NOT build persistence on top of TransferNetwork — use the Phase 4 connections.
- `BlockState.getValue(HopperBlock.FACING)` gives hopper facing; `HopperBlock.FACING` /
  `HopperBlock.ENABLED` are the relevant properties.
