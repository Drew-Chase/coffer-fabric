package com.github.drewchase.mixin;

import com.github.drewchase.connection.Connections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 4 destroy hook. {@link BlockEntity#preRemoveSideEffects} is called by the chunk only when a
 * block entity is actually removed/replaced (a real break), on the server, and NOT on chunk unload —
 * exactly the signal we want for tearing down connections. Vanilla uses it to drop container
 * contents; we additionally drop this endpoint's edges, notifying each counterpart (live object or,
 * if its chunk is unloaded, its side-store file) to remove the reciprocal edge.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityDestroyMixin {

    @Inject(method = "preRemoveSideEffects", at = @At("HEAD"))
    private void coffer$tearDownConnections(BlockPos pos, BlockState state, CallbackInfo ci) {
        Connections.destroy((BlockEntity) (Object) this);
    }
}
