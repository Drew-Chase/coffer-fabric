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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Phase 2 (lazy): diverts container contents out of the chunk-region NBT and into the UUID-keyed
 * side store, leaving only a stub (UUID + cached comparator signal) in the region.
 *
 * <p>On save, for a loaded container, the full contents are mirrored to the store and the {@code
 * "Items"} key is stripped from the region {@link ValueOutput} via {@code discard}. The hook is on
 * {@code saveWithFullMetadata} (the persistence entry point) at TAIL, so it runs AFTER the polymorphic
 * subclass {@code saveAdditional} has written {@code "Items"} — a hook on {@code saveAdditional} TAIL
 * would fire during the {@code super} call, before the subclass writes its items, and strip nothing.
 * A cached comparator signal is computed and written to the stub so redstone can read fullness
 * without a load.
 *
 * <p>On load, contents are NOT read eagerly — the block entity is marked unloaded (see {@link
 * com.github.drewchase.storage.CofferInventory}) and only the cached signal is restored. The first
 * interaction through {@code getItems()} triggers a synchronous hydrate from the store. The deferred
 * hook skips the hydrate's own {@code loadAdditional} pass, or it would re-mark the container unloaded.
 *
 * <p>Saving an UNLOADED container must not clobber its on-disk contents with an empty inventory, so
 * that case skips the store write and just re-emits the previously cached signal.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityStubStorageMixin {

    @Unique
    private static final String COFFER_SIGNAL_KEY = "coffer:signal";

    @Shadow
    public abstract void saveWithoutMetadata(ValueOutput output);

    @Shadow
    protected Level level;

    @Inject(method = "saveWithFullMetadata(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @At("TAIL"))
    private void coffer$divertContentsToStore(ValueOutput output, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self instanceof Container container)) {
            return;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store == null) {
            return;
        }

        CofferInventory lazy = (CofferInventory) self;
        UUID uuid = ((CofferUuidHolder) self).coffer$getOrCreateUuid();

        // The subclass saveAdditional has fully run by now, so "Items" is present in the region
        // output. It belongs in the side store, not the region: strip it so the region keeps the stub.
        output.discard("Items");

        if (!lazy.coffer$isLoaded()) {
            // Unloaded container: its in-memory contents are empty placeholders, not the truth.
            // Leave the existing store file untouched and re-emit the previously cached signal.
            output.putInt(COFFER_SIGNAL_KEY, lazy.coffer$cachedSignal());
            return;
        }

        int signal = AbstractContainerMenu.getRedstoneSignalFromContainer(container);
        lazy.coffer$setCachedSignal(signal);
        output.putInt(COFFER_SIGNAL_KEY, signal);

        // Re-serialize the full contents (with "Items") into a fresh tag for the store. This uses
        // saveWithoutMetadata, which is not the hooked method, so there is no re-entry here.
        RegistryAccess registries = serverLevel.registryAccess();
        CompoundTag fullContents;
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(self.problemPath(), Coffer.LOGGER)) {
            TagValueOutput capture = TagValueOutput.createWithContext(reporter, registries);
            this.saveWithoutMetadata(capture);
            fullContents = capture.buildResult();
        }
        // Mirror the connection set into the envelope so an offline counterpart's edge can be
        // removed by UUID without loading this chunk.
        store.write(uuid, serverLevel.dimension().identifier().toString(), fullContents,
                ((com.github.drewchase.connection.CofferConnections) self).coffer$connections());
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void coffer$deferContentsLoad(ValueInput input, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self instanceof Container)) {
            return;
        }
        if (!(this.level instanceof ServerLevel)) {
            return;
        }

        CofferInventory lazy = (CofferInventory) self;
        // Skip the hydrate pass: ensureLoaded -> loadCustomOnly -> loadAdditional re-enters here, and
        // re-marking unloaded / resetting the signal mid-hydrate would defeat the load.
        if (lazy.coffer$isHydrating()) {
            return;
        }
        // Restore the cached comparator signal from the stub so redstone works pre-load.
        lazy.coffer$setCachedSignal(input.getIntOr(COFFER_SIGNAL_KEY, 0));
        // Defer the real contents load until first interaction.
        lazy.coffer$markUnloaded();
    }
}
