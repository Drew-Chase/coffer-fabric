package com.github.drewchase.mixin;

import com.github.drewchase.storage.CofferInventory;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 2 comparator/redstone resolution. {@link AbstractContainerMenu#getRedstoneSignalFromContainer}
 * is the single funnel every container comparator uses; vanilla iterates {@code getContainerSize()}
 * / {@code getItem()}, which would force a lazy container to load on every comparator update and
 * defeat laziness near redstone.
 *
 * <p>Instead, for an unloaded {@link CofferInventory}, we short-circuit to the cached signal
 * computed at save time and restored from the stub on load. Loaded containers fall through to the
 * vanilla computation unchanged.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuComparatorMixin {

    @Inject(method = "getRedstoneSignalFromContainer", at = @At("HEAD"), cancellable = true)
    private static void coffer$cachedSignalWhenUnloaded(Container container, CallbackInfoReturnable<Integer> cir) {
        if (container instanceof CofferInventory lazy && !lazy.coffer$isLoaded()) {
            cir.setReturnValue(lazy.coffer$cachedSignal());
        }
    }
}
