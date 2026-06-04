package com.github.drewchase.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Runtime, position-keyed registry of transfer endpoints. Maps each <em>endpoint position</em> (a
 * block a transfer entity cares about) to the set of transfer-entity positions that care about it,
 * so a dirty signal at one position can fan out only to the entities actually connected to it.
 *
 * <p>This is the Phase 3 stand-in for the persistent, UUID-keyed bidirectional connection records
 * built in Phase 4. It lives only in memory and is rebuilt lazily as transfer entities tick, which
 * is why registration is idempotent and pruning is lazy. Phase 4 replaces this with the durable
 * resolver/connection graph.
 *
 * <p>Server-thread only. Keyed by dimension + packed {@link BlockPos} so coordinates that collide
 * across dimensions stay separate.
 */
public final class TransferNetwork {

    private final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> byDimension = new HashMap<>();

    /**
     * Registers that the transfer entity at {@code hopper} cares about changes at {@code endpoint}.
     * Idempotent.
     */
    public void register(Level level, BlockPos endpoint, BlockPos hopper) {
        this.byDimension
                .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(endpoint.asLong(), k -> new HashSet<>())
                .add(hopper.immutable());
    }

    /**
     * Fans a dirty signal out to every transfer entity registered for {@code pos}, waking each so it
     * re-attempts a transfer next tick. O(1) early-out when nothing is registered for {@code pos}.
     * Lazily prunes registrations whose transfer entity no longer exists.
     */
    public void markDirty(Level level, BlockPos pos) {
        Map<Long, Set<BlockPos>> dim = this.byDimension.get(level.dimension());
        if (dim == null) {
            return;
        }
        Set<BlockPos> hoppers = dim.get(pos.asLong());
        if (hoppers == null || hoppers.isEmpty()) {
            return;
        }

        Iterator<BlockPos> it = hoppers.iterator();
        while (it.hasNext()) {
            BlockPos hopperPos = it.next();
            BlockEntity be = level.getBlockEntity(hopperPos);
            if (be instanceof CofferTransfer transfer) {
                transfer.coffer$wake();
            } else if (be == null && level.isLoaded(hopperPos)) {
                // The block is loaded but is no longer a transfer entity: prune the stale edge.
                // (Unloaded positions are left alone — the entity re-registers when its chunk
                // reloads and ticks again.)
                it.remove();
            }
        }
        if (hoppers.isEmpty()) {
            dim.remove(pos.asLong());
        }
    }

    /**
     * Drops all registrations. Called on server stop so a fresh session starts clean.
     */
    public void clear() {
        this.byDimension.clear();
    }
}
