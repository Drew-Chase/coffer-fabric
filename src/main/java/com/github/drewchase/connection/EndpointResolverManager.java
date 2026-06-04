package com.github.drewchase.connection;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the active {@link EndpointResolver} for the running server. Mixins reach it through this
 * static accessor. Bound on server start and cleared on server stop (see {@link
 * com.github.drewchase.Coffer}).
 */
public final class EndpointResolverManager {

    @Nullable
    private static EndpointResolver active;

    private EndpointResolverManager() {
    }

    public static void bind() {
        active = new EndpointResolver();
    }

    public static void unbind() {
        if (active != null) {
            active.clear();
        }
        active = null;
    }

    /**
     * @return the active resolver, or {@code null} when no server is running. Callers must null-check.
     */
    @Nullable
    public static EndpointResolver get() {
        return active;
    }
}
