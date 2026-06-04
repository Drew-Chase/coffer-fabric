package com.github.drewchase.storage;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the active {@link InventoryStore} for the running server. A block-entity mixin has no clean
 * way to receive injected dependencies, so it reaches the store through this static accessor,
 * resolving the server from its own {@code level}.
 *
 * <p>Bound on {@code SERVER_STARTING} and cleared on {@code SERVER_STOPPED} (see {@link
 * com.github.drewchase.Coffer}). Integrated-server worlds reuse a single store per session.
 */
public final class InventoryStoreManager {

    @Nullable
    private static InventoryStore active;

    private InventoryStoreManager() {
    }

    public static void bind(MinecraftServer server) {
        active = InventoryStore.forServer(server);
    }

    public static void unbind() {
        active = null;
    }

    /**
     * @return the active store, or {@code null} if no server is running (e.g. during load before the
     * server is bound). Callers must null-check and skip the store interaction in that window.
     */
    @Nullable
    public static InventoryStore get() {
        return active;
    }
}
