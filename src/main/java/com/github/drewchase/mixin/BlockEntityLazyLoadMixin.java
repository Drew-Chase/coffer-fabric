package com.github.drewchase.mixin;

import com.github.drewchase.Coffer;
import com.github.drewchase.storage.CofferInventory;
import com.github.drewchase.storage.CofferUuidHolder;
import com.github.drewchase.storage.InventoryStore;
import com.github.drewchase.storage.InventoryStoreManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * Implements {@link CofferInventory} on every {@link BlockEntity}, driving Phase 2 lazy loading.
 *
 * <p>A container chunk-loaded by Phase 2 starts {@code unloaded}: its items are not in the region
 * NBT, only a cached redstone signal is. {@link #coffer$ensureLoaded()} pulls the full contents
 * from the side store and re-hydrates them through the block entity's own
 * {@link BlockEntity#loadCustomOnly(ValueInput)} on first interaction.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityLazyLoadMixin implements CofferInventory {

    @Shadow
    public abstract void loadCustomOnly(ValueInput input);

    @Shadow
    protected Level level;

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
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self instanceof Container)) {
            this.coffer$loaded = true;
            return;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            // Client side or no level yet: nothing to hydrate from. Treat as loaded to avoid
            // repeatedly re-checking on a path we cannot service.
            this.coffer$loaded = true;
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        UUID uuid = ((CofferUuidHolder) self).coffer$getUuid();
        if (store == null || uuid == null) {
            this.coffer$loaded = true;
            return;
        }

        CompoundTag contents = store.read(uuid);
        // Mark loaded BEFORE hydrating: loadCustomOnly -> loadAdditional re-enters our load mixin,
        // and the hydrating guard plus this flag prevent re-marking unloaded mid-hydration.
        this.coffer$loaded = true;
        if (contents == null) {
            return;
        }

        RegistryAccess registries = serverLevel.registryAccess();
        this.coffer$hydrating = true;
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(self.problemPath(), Coffer.LOGGER)) {
            ValueInput input = TagValueInput.create(reporter, registries, contents);
            this.loadCustomOnly(input);
        } finally {
            this.coffer$hydrating = false;
        }
    }
}
