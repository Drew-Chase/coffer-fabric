package com.github.drewchase.mixin;

import com.github.drewchase.storage.CofferInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 2 load trigger. Every real interaction with a {@link BaseContainerBlockEntity} — player GUI,
 * hopper/dropper transfers, dispenser fires — funnels through these concrete {@link
 * net.minecraft.world.Container} accessors, all of which read {@code getItems()}. Hydrating the
 * contents from the side store at the HEAD of each guarantees the inventory is populated before its
 * data is touched.
 *
 * <p>The comparator path is intentionally NOT here: it reads the cached stub signal via {@code
 * AbstractContainerMenuComparatorMixin} instead of forcing a load.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerAccessLoadTriggerMixin implements CofferInventory {

    @Inject(method = "getItem", at = @At("HEAD"))
    private void coffer$loadOnGetItem(int slot, CallbackInfoReturnable<ItemStack> cir) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "isEmpty", at = @At("HEAD"))
    private void coffer$loadOnIsEmpty(CallbackInfoReturnable<Boolean> cir) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void coffer$loadOnRemoveItem(int slot, int count, CallbackInfoReturnable<ItemStack> cir) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "removeItemNoUpdate", at = @At("HEAD"))
    private void coffer$loadOnRemoveItemNoUpdate(int slot, CallbackInfoReturnable<ItemStack> cir) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "setItem", at = @At("HEAD"))
    private void coffer$loadOnSetItem(int slot, ItemStack itemStack, CallbackInfo ci) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "clearContent", at = @At("HEAD"))
    private void coffer$loadOnClear(CallbackInfo ci) {
        this.coffer$ensureLoaded();
    }
}
