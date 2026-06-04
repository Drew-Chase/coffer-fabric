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
 * "Items"} key is stripped from the region {@link ValueOutput} via {@code discard}. The vanilla
 * subclass {@code saveAdditional} runs first and writes {@code "Items"}; this TAIL inject removes it
 * afterwards. A cached comparator signal is computed and written to the stub so redstone can read
 * fullness without a load.
 *
 * <p>On load, contents are NOT read eagerly — the block entity is marked unloaded (see {@link
 * com.github.drewchase.storage.CofferInventory}) and only the cached signal is restored. The first
 * interaction through {@code getItems()} triggers a synchronous hydrate from the store.
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

    /**
     * Reentrancy guard: {@link #saveWithoutMetadata} calls {@code saveAdditional}, so re-serializing
     * from inside the {@code saveAdditional} TAIL would recurse infinitely without this gate.
     */
    @Unique
    private boolean coffer$mirroring;

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void coffer$divertContentsToStore(ValueOutput output, CallbackInfo ci) {
        if (this.coffer$mirroring) {
            return;
        }
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

        // Items were just written to the region by the vanilla subclass saveAdditional. They belong
        // in the side store, not the region: strip them so the region keeps only the stub.
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

        RegistryAccess registries = serverLevel.registryAccess();
        this.coffer$mirroring = true;
        CompoundTag fullContents;
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(self.problemPath(), Coffer.LOGGER)) {
            TagValueOutput capture = TagValueOutput.createWithContext(reporter, registries);
            this.saveWithoutMetadata(capture);
            fullContents = capture.buildResult();
        } finally {
            this.coffer$mirroring = false;
        }
        // The captured tag still contains "Items" (full set) — exactly what we want in the store.
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
        // Restore the cached comparator signal from the stub so redstone works pre-load.
        lazy.coffer$setCachedSignal(input.getIntOr(COFFER_SIGNAL_KEY, 0));
        // Defer the real contents load until first interaction.
        lazy.coffer$markUnloaded();
    }
}
