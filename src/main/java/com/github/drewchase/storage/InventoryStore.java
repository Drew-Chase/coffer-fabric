package com.github.drewchase.storage;

import com.github.drewchase.Coffer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * UUID-keyed, on-disk store for full block-entity inventory contents, living under
 * {@code <world>/inventories/}. The chunk-region NBT keeps only a lightweight stub
 * (UUID + position); the heavyweight item/block-entity data is written here instead.
 *
 * <p>Layout is sharded by the first two hex characters of the UUID
 * ({@code inventories/<2hex>/<uuid>.dat}) to avoid a single directory holding millions of files.
 * Each file is GZip-compressed NBT.
 *
 * <p>UUIDs are global, but an inventory belongs to a dimension. Rather than per-dimension
 * directories (which complicate a single UUID-&gt;endpoint resolver), the dimension key is embedded
 * inside the stored file under {@link #DIMENSION_KEY}, with the contents under {@link #CONTENTS_KEY}.
 *
 * <p>Reads and writes are synchronous (Phase 2 threading decision: start synchronous, optimize in
 * Phase 6).
 */
public final class InventoryStore {

    private static final String ROOT_DIR_NAME = "inventories";
    private static final String FILE_EXTENSION = ".dat";
    private static final String DIMENSION_KEY = "coffer:dimension";
    private static final String CONTENTS_KEY = "coffer:contents";
    private static final String CONNECTIONS_KEY = "coffer:connections";

    private final Path rootDir;

    private InventoryStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * Creates a store rooted at {@code <world>/inventories/} for the given server.
     */
    public static InventoryStore forServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        return new InventoryStore(worldRoot.resolve(ROOT_DIR_NAME));
    }

    /**
     * Resolves the on-disk path for a UUID using the 2-hex shard layout.
     */
    private Path pathFor(UUID uuid) {
        String key = uuid.toString().toLowerCase(Locale.ROOT);
        String shard = key.substring(0, 2);
        return rootDir.resolve(shard).resolve(key + FILE_EXTENSION);
    }

    /**
     * Writes the full block-entity NBT for {@code uuid} to the store, tagging it with its dimension,
     * creating shard directories as needed. Failures are logged, not thrown — a missing side file is
     * recovered as an empty load.
     *
     * @param dimensionId the dimension this inventory belongs to (e.g. {@code minecraft:overworld}).
     */
    public void write(UUID uuid, String dimensionId, CompoundTag contents, Set<UUID> connections) {
        Path file = pathFor(uuid);
        CompoundTag envelope = new CompoundTag();
        envelope.putString(DIMENSION_KEY, dimensionId);
        envelope.put(CONTENTS_KEY, contents);
        envelope.put(CONNECTIONS_KEY, encodeConnections(connections));
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(envelope, file);
        } catch (IOException e) {
            Coffer.LOGGER.error("Failed to write inventory contents for {} to {}", uuid, file, e);
        }
    }

    /**
     * Removes the edge to {@code other} from the persisted connection set of an OFFLINE endpoint
     * {@code uuid}, by rewriting its store file. Used by destroy notification when a counterpart's
     * chunk is unloaded, so the edge is dropped without loading it.
     *
     * @return {@code true} if an edge was removed and the file rewritten.
     */
    public boolean removeConnectionEdge(UUID uuid, UUID other) {
        Path file = pathFor(uuid);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            CompoundTag envelope = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Set<UUID> connections = decodeConnections(envelope.get(CONNECTIONS_KEY));
            if (!connections.remove(other)) {
                return false;
            }
            envelope.put(CONNECTIONS_KEY, encodeConnections(connections));
            NbtIo.writeCompressed(envelope, file);
            return true;
        } catch (IOException e) {
            Coffer.LOGGER.error("Failed to remove connection edge {} from {} at {}", other, uuid, file, e);
            return false;
        }
    }

    private static Tag encodeConnections(Set<UUID> connections) {
        return UUIDUtil.CODEC_SET.encodeStart(NbtOps.INSTANCE, connections).result().orElseGet(ListTag::new);
    }

    private static Set<UUID> decodeConnections(@Nullable Tag tag) {
        if (tag == null) {
            return new HashSet<>();
        }
        return UUIDUtil.CODEC_SET.parse(NbtOps.INSTANCE, tag).result().orElseGet(HashSet::new);
    }

    /**
     * Reads the full block-entity contents NBT for {@code uuid}, or {@code null} if no file exists
     * yet. The dimension envelope is unwrapped; only the contents tag is returned.
     */
    @Nullable
    public CompoundTag read(UUID uuid) {
        Path file = pathFor(uuid);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            CompoundTag envelope = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return envelope.getCompound(CONTENTS_KEY).orElse(null);
        } catch (IOException e) {
            Coffer.LOGGER.error("Failed to read inventory contents for {} from {}", uuid, file, e);
            return null;
        }
    }

    /**
     * @return {@code true} if a contents file exists for {@code uuid}.
     */
    public boolean exists(UUID uuid) {
        return Files.isRegularFile(pathFor(uuid));
    }

    /**
     * Deletes the store file for {@code uuid}, if present. Called when an endpoint is permanently
     * removed (broken) so it does not leave an orphaned side-store file. Best-effort; failures are
     * logged, not thrown.
     */
    public void delete(UUID uuid) {
        Path file = pathFor(uuid);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Coffer.LOGGER.error("Failed to delete orphaned inventory file for {} at {}", uuid, file, e);
        }
    }
}
