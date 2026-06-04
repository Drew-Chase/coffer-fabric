package com.github.drewchase.storage;

/**
 * Duck interface implemented on container {@code BlockEntity}s via mixin to drive lazy loading.
 *
 * <p>Phase 2 model: when a chunk loads, a container's contents are NOT read from the region NBT
 * (they no longer live there — see {@code BlockEntityStubStorageMixin}). Instead the block entity is
 * marked unloaded and carries only a cached redstone-signal summary from the stub. The full item
 * data is pulled synchronously from the side store on the first real interaction (GUI open, hopper
 * rescan, ...), which all funnel through {@code getItems()}.
 *
 * <p>The comparator path is deliberately excluded: it reads {@link #coffer$cachedSignal()} from the
 * stub instead of forcing a load, so redstone near an idle container does not defeat laziness.
 */
public interface CofferInventory {

    /**
     * @return {@code true} once the contents have been hydrated from the side store (or there was
     * nothing to hydrate). A freshly chunk-loaded container starts {@code false}.
     */
    boolean coffer$isLoaded();

    /**
     * Hydrates contents from the side store if not already loaded. Idempotent and safe to call on
     * every access path; after the first call it is a single boolean check. No-op off the server
     * thread / when no store is bound.
     */
    void coffer$ensureLoaded();

    /**
     * Marks the container unloaded so the next interaction re-hydrates from the store. Called from
     * the load path instead of eagerly reading items.
     */
    void coffer$markUnloaded();

    /**
     * @return {@code true} while contents are being hydrated from the store. The deferred-load hook
     * must check this and do nothing, otherwise the hydrate's own {@code loadAdditional} pass would
     * re-mark the container unloaded and reset the cached signal.
     */
    boolean coffer$isHydrating();

    /**
     * @return the cached comparator signal (0-15) read from the stub, served to redstone without
     * loading contents.
     */
    int coffer$cachedSignal();

    /**
     * Sets the cached comparator signal, computed at save time and read back from the stub on load.
     */
    void coffer$setCachedSignal(int signal);
}
