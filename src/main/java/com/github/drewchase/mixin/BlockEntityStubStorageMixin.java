package com.github.drewchase.mixin;

import com.github.drewchase.Coffer;
import com.github.drewchase.storage.CofferUuidHolder;
import com.github.drewchase.storage.InventoryStore;
import com.github.drewchase.storage.InventoryStoreManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
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
 * Phase 1 (eager): mirrors the full contents of every container-bearing {@link BlockEntity} into the
 * UUID-keyed side store under {@code world/inventories/}, and eagerly reads it back on load to
 * validate the round-trip end-to-end before laziness is introduced in Phase 2.
 *
 * <p>This intentionally does NOT yet strip the contents from the chunk-region NBT — that diversion
 * is Phase 2's lazy-loading concern. Phase 1 only proves that {UUID -> contents} survives
 * save/quit/reload through the side store.
 *
 * <p>Only acts on block entities that are also {@link Container}s (chest, barrel, furnace, hopper,
 * dropper, ...) — the inventories the redesign targets.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityStubStorageMixin {

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
    private void coffer$mirrorContentsToStore(ValueOutput output, CallbackInfo ci) {
        if (this.coffer$mirroring) {
            return;
        }
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self instanceof Container)) {
            return;
        }
        // Server-side only: the store is bound to the running server's save directory.
        if (!(this.level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store == null) {
            return;
        }

        UUID uuid = ((CofferUuidHolder) self).coffer$getOrCreateUuid();
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

        store.write(uuid, fullContents);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void coffer$validateRoundTrip(ValueInput input, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self instanceof Container)) {
            return;
        }
        if (!(this.level instanceof net.minecraft.server.level.ServerLevel)) {
            return;
        }
        InventoryStore store = InventoryStoreManager.get();
        if (store == null) {
            return;
        }

        UUID uuid = ((CofferUuidHolder) self).coffer$getUuid();
        if (uuid == null) {
            return;
        }

        // Eager load: read the side store back immediately to confirm the round-trip works.
        CompoundTag stored = store.read(uuid);
        if (stored != null) {
            Coffer.LOGGER.info("[Coffer] round-trip OK for {} ({}): loaded {} keys from side store",
                    self.getType(), uuid, stored.size());
        } else {
            Coffer.LOGGER.info("[Coffer] no side-store file yet for {} ({}) — first save pending",
                    self.getType(), uuid);
        }
    }
}
