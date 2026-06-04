package com.github.drewchase.storage;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Duck interface implemented on {@code BlockEntity} via mixin. Gives every block entity a stable
 * UUID identity that is independent of its {@link net.minecraft.core.BlockPos}, so its contents can
 * be keyed in the side store under {@code world/inventories/}.
 *
 * <p>The UUID is assigned lazily on first save (see {@code BlockEntityUuidMixin}) and persisted in
 * the chunk-region stub.
 */
public interface CofferUuidHolder {

    /**
     * @return the stable UUID for this block entity, or {@code null} if one has not been assigned yet.
     */
    @Nullable
    UUID coffer$getUuid();

    /**
     * Assigns the stable UUID for this block entity. Set once on first creation/save and persisted.
     */
    void coffer$setUuid(UUID uuid);

    /**
     * @return the existing UUID, assigning and returning a fresh random one if none was set.
     */
    UUID coffer$getOrCreateUuid();
}
