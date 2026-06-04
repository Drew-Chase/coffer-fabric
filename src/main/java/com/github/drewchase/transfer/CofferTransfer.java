package com.github.drewchase.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Duck interface implemented on transfer entities (currently {@code HopperBlockEntity}) via mixin to
 * drive Phase 3 event-driven transfers.
 *
 * <p>Instead of scanning its neighbours every tick, a transfer entity goes <em>dormant</em> after a
 * tick where it attempts a move and nothing happens (a cached {@code BLOCKED} decision). It stays
 * dormant — doing zero work per tick — until a connected inventory marks dirty and {@link
 * #coffer$wake() wakes} it, at which point it re-attempts on the next tick (a re-evaluated {@code
 * CAN_ACT}).
 *
 * <p>The methods form the boundary between the static vanilla tick (which only has the entity as a
 * parameter, so it must reach instance state through this interface) and the per-entity dormancy
 * state held in the mixin.
 */
public interface CofferTransfer {

    /**
     * Wakes the transfer entity so it re-attempts a transfer on its next tick. Called from the
     * dirty-event fan-out. Cheap and idempotent — it only clears the dormant flag.
     */
    void coffer$wake();

    /**
     * Invoked at the head of the vanilla transfer tick. Lazily registers this entity's endpoints in
     * the {@link TransferNetwork} on first call.
     *
     * @return {@code true} if the entity is dormant and the vanilla tick body should be skipped.
     */
    boolean coffer$beginTransferTick(Level level, BlockPos pos, BlockState state);

    /**
     * Invoked at the return of the vanilla transfer tick (only when it was not skipped). Caches a
     * {@code BLOCKED} decision and goes dormant if the tick attempted a move and nothing happened.
     */
    void coffer$endTransferTick();
}
