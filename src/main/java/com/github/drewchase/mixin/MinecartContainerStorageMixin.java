package com.github.drewchase.mixin;

import com.github.drewchase.Coffer;
import com.github.drewchase.storage.CofferInventory;
import com.github.drewchase.storage.InventoryStore;
import com.github.drewchase.storage.InventoryStoreManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Brings container minecarts ({@link AbstractMinecartContainer} — chest and hopper minecarts) into
 * the Coffer lazy-storage model via the ENTITY serialization path, which the block-entity mixins do
 * not cover. Contents are diverted to the UUID-keyed side store (keyed by the entity's own UUID) and
 * loaded on first access, mirroring the block-container behaviour.
 *
 * <p>Differences from the block path, by design:
 * <ul>
 *   <li>Identity is the entity's existing {@code getUUID()} — no stub UUID is assigned/persisted.</li>
 *   <li>No connections or transfer dormancy: a minecart moves, so it has no fixed connected
 *       endpoints; its {@code suckInItems} polling stays vanilla (it uses a different code path than
 *       the block hopper's {@code pushItemsTick} that Phase 3 gates).</li>
 * </ul>
 *
 * <p>The comparator/redstone cache is reused for free: {@code applyNaturalSlowdown} reads
 * {@code getRedstoneSignalFromContainer(this)} every movement tick, and the comparator mixin returns
 * the cached signal while unloaded, so physics does not force a load.
 *
 * <p>The divert hook is at the TAIL of {@code AbstractMinecartContainer.addAdditionalSaveData}, which
 * writes {@code "Items"} itself (via {@code addChestVehicleSaveData}) — so unlike the block path the
 * TAIL genuinely runs after the items are written.
 */
@Mixin(AbstractMinecartContainer.class)
public abstract class MinecartContainerStorageMixin implements CofferInventory {

    @Unique
    private static final String COFFER_SIGNAL_KEY = "coffer:signal";

    @Unique
    private boolean coffer$loaded = true;
    @Unique
    private boolean coffer$hydrating;
    @Unique
    private int coffer$signal;

    @Override
    public boolean coffer$isLoaded() {
        return this.coffer$loaded;
    }

    @Override
    public void coffer$markUnloaded() {
        this.coffer$loaded = false;
    }

    @Override
    public boolean coffer$isHydrating() {
        return this.coffer$hydrating;
    }

    @Override
    public int coffer$cachedSignal() {
        return this.coffer$signal;
    }

    @Override
    public void coffer$setCachedSignal(int signal) {
        this.coffer$signal = signal;
    }

    @Override
    public void coffer$ensureLoaded() {
        if (this.coffer$loaded || this.coffer$hydrating) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel serverLevel)) {
            this.coffer$loaded = true;
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store == null) {
            this.coffer$loaded = true;
            return;
        }

        CompoundTag contents = store.read(self.getUUID());
        this.coffer$loaded = true;
        if (contents == null) {
            return;
        }
        this.coffer$hydrating = true;
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(self.problemPath(), Coffer.LOGGER)) {
            ValueInput input = TagValueInput.create(reporter, serverLevel.registryAccess(), contents);
            ((ContainerEntity) (Object) this).readChestVehicleSaveData(input);
        } finally {
            this.coffer$hydrating = false;
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void coffer$divertContents(ValueOutput output, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store == null) {
            return;
        }

        // Items were just written by addChestVehicleSaveData; move them to the side store.
        output.discard("Items");

        if (!this.coffer$loaded) {
            // Unloaded: in-memory contents are empty placeholders — keep the store file, re-emit signal.
            output.putInt(COFFER_SIGNAL_KEY, this.coffer$signal);
            return;
        }

        int signal = AbstractContainerMenu.getRedstoneSignalFromContainer((Container) (Object) this);
        this.coffer$signal = signal;
        output.putInt(COFFER_SIGNAL_KEY, signal);

        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(self.problemPath(), Coffer.LOGGER)) {
            TagValueOutput capture = TagValueOutput.createWithContext(reporter, serverLevel.registryAccess());
            ((ContainerEntity) (Object) this).addChestVehicleSaveData(capture);
            CompoundTag contents = capture.buildResult();
            // Minecarts have no connections, so the connection set is always empty.
            store.write(self.getUUID(), serverLevel.dimension().identifier().toString(), contents, Set.of());
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void coffer$deferContents(ValueInput input, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            return;
        }
        // Skip the hydrate pass (readChestVehicleSaveData re-enters this), or it would re-defer.
        if (this.coffer$hydrating) {
            return;
        }
        this.coffer$signal = input.getIntOr(COFFER_SIGNAL_KEY, 0);
        this.coffer$loaded = false;
    }

    @Inject(method = "getItem", at = @At("HEAD"))
    private void coffer$loadOnGetItem(int slot, CallbackInfoReturnable<ItemStack> cir) {
        this.coffer$ensureLoaded();
    }

    @Inject(method = "setItem", at = @At("HEAD"))
    private void coffer$loadOnSetItem(int slot, ItemStack itemStack, CallbackInfo ci) {
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

    @Inject(method = "remove", at = @At("TAIL"))
    private void coffer$deleteStoreOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() || !reason.shouldDestroy()) {
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store != null) {
            store.delete(self.getUUID());
        }
    }
}
