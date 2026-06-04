package com.github.drewchase.connection;

import com.github.drewchase.storage.CofferUuidHolder;
import com.github.drewchase.storage.InventoryStore;
import com.github.drewchase.storage.InventoryStoreManager;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Iterator;
import java.util.UUID;

/**
 * Connection lifecycle helpers shared by the mixins: forming bidirectional edges, the load-time
 * freshness prune, and destroy teardown with offline-counterpart edge removal.
 *
 * <p>An "endpoint" is any {@link BlockEntity} (it implements {@link CofferUuidHolder} and {@link
 * CofferConnections} via mixin). Server-thread only.
 */
public final class Connections {

    private Connections() {
    }

    /**
     * Forms a bidirectional connection between two endpoints, assigning each a UUID if needed. Both
     * sides store the other's UUID. Idempotent.
     */
    public static void connect(BlockEntity a, BlockEntity b) {
        if (a == b) {
            return;
        }
        UUID ua = ((CofferUuidHolder) a).coffer$getOrCreateUuid();
        UUID ub = ((CofferUuidHolder) b).coffer$getOrCreateUuid();
        if (ua.equals(ub)) {
            return;
        }
        ((CofferConnections) a).coffer$addConnection(ub);
        ((CofferConnections) b).coffer$addConnection(ua);
    }

    /**
     * Runs the one-time, load-triggered freshness check on {@code self}: prunes any connection whose
     * UUID resolves to neither a live endpoint nor a persisted store file (e.g. a counterpart broken
     * while {@code self} was unloaded). No-op if the flag is not raised.
     */
    public static void checkFreshness(BlockEntity self) {
        CofferConnections conn = (CofferConnections) self;
        if (!conn.coffer$needsFreshnessCheck()) {
            return;
        }
        EndpointResolver resolver = EndpointResolverManager.get();
        if (resolver == null) {
            return;
        }
        Iterator<UUID> it = conn.coffer$connections().iterator();
        while (it.hasNext()) {
            if (!resolver.exists(it.next())) {
                it.remove();
            }
        }
        conn.coffer$clearFreshnessCheck();
    }

    /**
     * Tears down every edge of a broken endpoint, dropping the reciprocal edge on each counterpart.
     * A live counterpart is edited directly; an offline counterpart has its edge removed from its
     * side-store file by UUID (no chunk load required).
     */
    public static void destroy(BlockEntity self) {
        UUID selfUuid = ((CofferUuidHolder) self).coffer$getUuid();
        if (selfUuid == null) {
            return;
        }
        CofferConnections conn = (CofferConnections) self;
        EndpointResolver resolver = EndpointResolverManager.get();
        InventoryStore store = InventoryStoreManager.get();

        for (UUID other : conn.coffer$connections()) {
            BlockEntity live = resolver != null ? resolver.resolveLive(other) : null;
            if (live instanceof CofferConnections counterpart) {
                counterpart.coffer$removeConnection(selfUuid);
            } else if (store != null) {
                store.removeConnectionEdge(other, selfUuid);
            }
        }
        conn.coffer$connections().clear();
    }
}
