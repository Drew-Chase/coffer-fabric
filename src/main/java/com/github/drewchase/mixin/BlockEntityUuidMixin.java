package com.github.drewchase.mixin;

import com.github.drewchase.connection.EndpointResolver;
import com.github.drewchase.connection.EndpointResolverManager;
import com.github.drewchase.storage.CofferUuidHolder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Attaches a stable UUID identity to every {@link BlockEntity} via the {@link CofferUuidHolder}
 * duck interface, persists that UUID into the chunk-region stub, and keeps the endpoint indexed in
 * the {@link EndpointResolver} so connections can resolve it by UUID.
 *
 * <p>The UUID is the key under which the block entity's full contents live in the side store
 * ({@code world/inventories/<uuid>}). It is assigned lazily on first save and read back on load,
 * so it survives the save/quit/reload round-trip. (MC 26.1.2: serialization is via
 * {@link ValueOutput}/{@link ValueInput}; {@code saveAdditional}/{@code loadAdditional} are the
 * subclass content hooks.)
 *
 * <p>Resolver indexing is driven from here — the UUID lifecycle hub — rather than from the
 * connections mixin, so it does not depend on the order in which the two stub mixins' load callbacks
 * run. Registration fires whenever both the UUID and a server level are known (UUID set/created, or
 * {@code setLevel}); deregistration fires on {@code setRemoved} (unload or break).
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityUuidMixin implements CofferUuidHolder {

    @Unique
    private static final String COFFER_UUID_KEY = "coffer:uuid";

    @Shadow
    @Nullable
    protected Level level;

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
        this.coffer$tryRegister();
    }

    @Override
    public UUID coffer$getOrCreateUuid() {
        if (this.coffer$uuid == null) {
            this.coffer$uuid = UUID.randomUUID();
        }
        this.coffer$tryRegister();
        return this.coffer$uuid;
    }

    @Unique
    private void coffer$tryRegister() {
        if (this.coffer$uuid == null || !(this.level instanceof ServerLevel)) {
            return;
        }
        EndpointResolver resolver = EndpointResolverManager.get();
        if (resolver != null) {
            resolver.register(this.coffer$uuid, (BlockEntity) (Object) this);
        }
    }

    /**
     * Writes the UUID stub field. Assigns one on first save so freshly placed block entities get an
     * identity without a dedicated placement hook.
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
        input.read(COFFER_UUID_KEY, UUIDUtil.CODEC).ifPresent(this::coffer$setUuid);
    }

    /**
     * Index the endpoint once it enters a server level (covers the load order where the level is
     * attached after the UUID was already read).
     */
    @Inject(method = "setLevel", at = @At("TAIL"))
    private void coffer$registerOnSetLevel(Level level, CallbackInfo ci) {
        this.coffer$tryRegister();
    }

    /**
     * Drop the endpoint from the live index on unload or removal. The persisted connection record is
     * untouched — only the in-memory mapping is cleared.
     */
    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void coffer$unregisterOnRemoved(CallbackInfo ci) {
        if (this.coffer$uuid == null) {
            return;
        }
        EndpointResolver resolver = EndpointResolverManager.get();
        if (resolver != null) {
            resolver.unregister(this.coffer$uuid, (BlockEntity) (Object) this);
        }
    }
}
