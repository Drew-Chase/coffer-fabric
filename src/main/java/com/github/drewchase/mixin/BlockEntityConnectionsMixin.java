package com.github.drewchase.mixin;

import com.github.drewchase.connection.CofferConnections;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@link CofferConnections} on every {@link BlockEntity} and persists the endpoint's set
 * of connected UUIDs into the chunk-region stub (cheap to read on load). Resolver indexing is
 * handled separately by {@code BlockEntityUuidMixin}; this mixin only owns the connection set and
 * its persistence.
 *
 * <p>On load the freshness flag is raised so the first user of the connections prunes edges that no
 * longer resolve (see {@code BlockEntityDestroyMixin} / the connection helpers).
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityConnectionsMixin implements CofferConnections {

    @Unique
    private static final String COFFER_CONNECTIONS_KEY = "coffer:connections";

    @Shadow
    public abstract void setChanged();

    @Unique
    private final Set<UUID> coffer$connections = new HashSet<>();

    @Unique
    private boolean coffer$freshnessPending;

    @Override
    public Set<UUID> coffer$connections() {
        return this.coffer$connections;
    }

    @Override
    public boolean coffer$addConnection(UUID other) {
        if (this.coffer$connections.add(other)) {
            this.setChanged();
            return true;
        }
        return false;
    }

    @Override
    public boolean coffer$removeConnection(UUID other) {
        if (this.coffer$connections.remove(other)) {
            this.setChanged();
            return true;
        }
        return false;
    }

    @Override
    public void coffer$setConnections(Set<UUID> connections) {
        this.coffer$connections.clear();
        this.coffer$connections.addAll(connections);
    }

    @Override
    public boolean coffer$needsFreshnessCheck() {
        return this.coffer$freshnessPending;
    }

    @Override
    public void coffer$clearFreshnessCheck() {
        this.coffer$freshnessPending = false;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void coffer$writeConnections(ValueOutput output, CallbackInfo ci) {
        if (!this.coffer$connections.isEmpty()) {
            output.store(COFFER_CONNECTIONS_KEY, UUIDUtil.CODEC_SET, this.coffer$connections);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void coffer$readConnections(ValueInput input, CallbackInfo ci) {
        input.read(COFFER_CONNECTIONS_KEY, UUIDUtil.CODEC_SET).ifPresent(this::coffer$setConnections);
        // Raise the freshness flag so dead edges (counterpart broken while we were unloaded) are
        // pruned on first use.
        this.coffer$freshnessPending = true;
    }
}
