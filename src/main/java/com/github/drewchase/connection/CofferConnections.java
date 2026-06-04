package com.github.drewchase.connection;

import java.util.Set;
import java.util.UUID;

/**
 * Duck interface implemented on every {@code BlockEntity} via mixin, holding the endpoint's
 * <em>connection record</em>: the set of UUIDs of the endpoints it is connected to.
 *
 * <p>Connections are bidirectional — when a transfer entity connects to an inventory, each stores
 * the other's UUID. The set is persisted in the chunk-region stub (cheap to read on load) and
 * mirrored into the side-store envelope (so an offline counterpart's edge can be removed by UUID
 * without loading its chunk). See {@code EndpointResolver} for resolution and {@code
 * BlockEntityDestroyMixin} for teardown.
 */
public interface CofferConnections {

    /**
     * @return the live set of connected endpoint UUIDs. Mutating it directly is allowed for internal
     * callers (freshness pruning); prefer {@link #coffer$addConnection}/{@link
     * #coffer$removeConnection} for edge changes so the dirty/persistence bookkeeping stays correct.
     */
    Set<UUID> coffer$connections();

    /**
     * Adds a connection edge to {@code other}. Marks the endpoint changed so the new edge persists.
     *
     * @return {@code true} if the edge was newly added.
     */
    boolean coffer$addConnection(UUID other);

    /**
     * Removes the connection edge to {@code other}. Marks the endpoint changed so the removal
     * persists.
     *
     * @return {@code true} if an edge was removed.
     */
    boolean coffer$removeConnection(UUID other);

    /**
     * Replaces the connection set wholesale. Used by the load path to restore persisted edges.
     */
    void coffer$setConnections(Set<UUID> connections);

    /**
     * @return {@code true} if this endpoint was just loaded and its edges have not yet been
     * freshness-checked against the current world.
     */
    boolean coffer$needsFreshnessCheck();

    /**
     * Clears the freshness-check flag once the one-time prune has run.
     */
    void coffer$clearFreshnessCheck();
}
