package com.github.drewchase.mixin;

import com.github.drewchase.transfer.TransferNetwork;
import com.github.drewchase.transfer.TransferNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 3 dirty-event source. {@link BlockEntity#setChanged()} is the single funnel every content
 * mutation flows through — player edits, hopper inserts, furnace results, dispenser fires all reach
 * the static {@code setChanged(Level, BlockPos, BlockState)} it delegates to. Hooking it here emits
 * one dirty signal per mutation into the {@link TransferNetwork}, which fans out only to the
 * transfer entities registered for that position (O(1) early-out otherwise).
 *
 * <p>The signal is deliberately "dumb": dirty means "rescan". Server-side only.
 */
@Mixin(BlockEntity.class)
public abstract class MarkDirtyMixin {

    @Inject(
            method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD")
    )
    private static void coffer$emitDirty(Level level, BlockPos worldPosition, BlockState blockState, CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        TransferNetwork net = TransferNetworkManager.get();
        if (net != null) {
            net.markDirty(level, worldPosition);
        }
    }
}
