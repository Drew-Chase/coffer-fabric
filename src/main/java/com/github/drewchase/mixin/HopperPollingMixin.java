package com.github.drewchase.mixin;

import com.github.drewchase.transfer.CofferTransfer;
import com.github.drewchase.transfer.TransferNetwork;
import com.github.drewchase.transfer.TransferNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 3: replaces the hopper's per-tick polling with event-driven, dormancy-cached transfers.
 *
 * <p>Vanilla {@code pushItemsTick} runs every tick, decrementing a cooldown and — when the cooldown
 * expires — scanning the source/target containers for items to move. An idle hopper repeats that
 * scan forever. This mixin makes an idle hopper go dormant and skip the scan entirely until a
 * connected inventory marks it dirty.
 *
 * <p>The vanilla transfer logic is reused unchanged (it is only gated, not rewritten), so a hopper
 * that is actively moving items keeps vanilla's exact 8-tick cadence — preserving redstone/timing
 * parity. Vanilla's {@code setCooldown(8)} after a successful move is also the feedback-loop guard:
 * waking only sets a flag, and the move itself is still rate-limited by the cooldown.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperPollingMixin implements CofferTransfer {

    @Shadow
    private int cooldownTime;

    @Unique
    private boolean coffer$dormant;

    @Unique
    private boolean coffer$registered;

    @Override
    public void coffer$wake() {
        this.coffer$dormant = false;
    }

    @Override
    public boolean coffer$beginTransferTick(Level level, BlockPos pos, BlockState state) {
        this.coffer$ensureRegistered(level, pos, state);
        return this.coffer$dormant;
    }

    @Override
    public void coffer$endTransferTick() {
        // Reached only on an awake tick. A successful move sets the cooldown to 8; if the cooldown is
        // not set, the hopper just attempted a transfer and nothing happened — cache BLOCKED.
        if (this.cooldownTime <= 0) {
            this.coffer$dormant = true;
        }
    }

    @Unique
    private void coffer$ensureRegistered(Level level, BlockPos pos, BlockState state) {
        if (this.coffer$registered) {
            return;
        }
        TransferNetwork net = TransferNetworkManager.get();
        if (net == null) {
            return;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        net.register(level, pos.relative(facing), pos); // eject target (the block it faces)
        net.register(level, pos.above(), pos);          // suck source (the block above)
        net.register(level, pos, pos);                  // self (e.g. a player inserting items)
        this.coffer$registered = true;
    }

    @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
    private static void coffer$gatePolling(Level level, BlockPos pos, BlockState state, HopperBlockEntity entity, CallbackInfo ci) {
        if (((CofferTransfer) entity).coffer$beginTransferTick(level, pos, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "pushItemsTick", at = @At("RETURN"))
    private static void coffer$cacheDecision(Level level, BlockPos pos, BlockState state, HopperBlockEntity entity, CallbackInfo ci) {
        ((CofferTransfer) entity).coffer$endTransferTick();
    }
}
