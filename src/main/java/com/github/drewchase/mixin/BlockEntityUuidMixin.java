package com.github.drewchase.mixin;

import com.github.drewchase.storage.CofferUuidHolder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Attaches a stable UUID identity to every {@link BlockEntity} via the {@link CofferUuidHolder}
 * duck interface, and persists that UUID into the chunk-region stub.
 *
 * <p>The UUID is the key under which the block entity's full contents live in the side store
 * ({@code world/inventories/<uuid>}). It is assigned lazily on first save and read back on load,
 * so it survives the save/quit/reload round-trip. (MC 26.1.2: serialization is via
 * {@link ValueOutput}/{@link ValueInput}; {@code saveAdditional}/{@code loadAdditional} are the
 * subclass content hooks.)
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityUuidMixin implements CofferUuidHolder {

    @Unique
    private static final String COFFER_UUID_KEY = "coffer:uuid";

    @Unique
    @Nullable
    private UUID coffer$uuid;

    @Override
    @Nullable
    public UUID coffer$getUuid() {
        return this.coffer$uuid;
    }

    @Override
    public void coffer$setUuid(UUID uuid) {
        this.coffer$uuid = uuid;
    }

    @Override
    public UUID coffer$getOrCreateUuid() {
        if (this.coffer$uuid == null) {
            this.coffer$uuid = UUID.randomUUID();
        }
        return this.coffer$uuid;
    }

    /**
     * Writes the UUID stub field. Assigns one on first save so freshly placed block entities get an
     * identity without a dedicated placement hook (that comes in Phase 4 for connections).
     */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void coffer$writeUuidStub(ValueOutput output, CallbackInfo ci) {
        output.store(COFFER_UUID_KEY, UUIDUtil.CODEC, this.coffer$getOrCreateUuid());
    }

    /**
     * Reads the UUID stub field back on load so the identity is stable across reloads.
     */
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void coffer$readUuidStub(ValueInput input, CallbackInfo ci) {
        input.read(COFFER_UUID_KEY, UUIDUtil.CODEC).ifPresent(uuid -> this.coffer$uuid = uuid);
    }
}
