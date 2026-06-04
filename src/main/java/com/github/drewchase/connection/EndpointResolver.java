package com.github.drewchase.connection;

import com.github.drewchase.storage.InventoryStore;
import com.github.drewchase.storage.InventoryStoreManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * UUID-&gt;endpoint registry. Resolves a connection UUID to a live {@link BlockEntity} loaded in the
 * world, or — for the existence test used by freshness checks — falls back to whether a side-store
 * file exists for that UUID.
 *
 * <p>Central to the connection system: the dirty fan-out, the load-time freshness check, and
 * destroy notification all resolve counterparts through here. Server-thread only; the live index is
 * rebuilt as block entities load and unload.
 */
public final class EndpointResolver {

    private final Map<UUID, BlockEntity> live = new HashMap<>();

    /**
     * Indexes a loaded endpoint under its UUID. Called when a block entity's UUID becomes known and
     * it has a server level.
     */
    public void register(UUID uuid, BlockEntity endpoint) {
        this.live.put(uuid, endpoint);
    }

    /**
     * Removes an endpoint from the live index. Called on unload or removal. Only drops the mapping if
     * it still points at {@code endpoint}, so a re-registered position is not clobbered.
     */
    public void unregister(UUID uuid, BlockEntity endpoint) {
        this.live.remove(uuid, endpoint);
    }

    /**
     * @return the live block entity for {@code uuid}, or {@code null} if none is currently loaded.
     */
    @Nullable
    public BlockEntity resolveLive(UUID uuid) {
        return this.live.get(uuid);
    }

    /**
     * @return {@code true} if {@code uuid} resolves to a live endpoint or has a persisted side-store
     * file. Used by the freshness check to decide whether an edge is still valid.
     */
    public boolean exists(UUID uuid) {
        if (this.live.containsKey(uuid)) {
            return true;
        }
        InventoryStore store = InventoryStoreManager.get();
        return store != null && store.exists(uuid);
    }

    public void clear() {
        this.live.clear();
    }
}
