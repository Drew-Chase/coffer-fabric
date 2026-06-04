package com.github.drewchase.transfer;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the active {@link TransferNetwork} for the running server. Mixins reach the network through
 * this static accessor (they have no way to receive injected dependencies).
 *
 * <p>Bound on server start and cleared on server stop (see {@link com.github.drewchase.Coffer}), so
 * registrations never leak across integrated-server sessions.
 */
public final class TransferNetworkManager {

    @Nullable
    private static TransferNetwork active;

    private TransferNetworkManager() {
    }

    public static void bind() {
        active = new TransferNetwork();
    }

    public static void unbind() {
        if (active != null) {
            active.clear();
        }
        active = null;
    }

    /**
     * @return the active network, or {@code null} when no server is running. Callers must null-check.
     */
    @Nullable
    public static TransferNetwork get() {
        return active;
    }
}
