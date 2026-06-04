package com.github.drewchase.access;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the single most-recent access record for an inventory (Phase 5): when the last slot change
 * happened, which slot, and what kind. Audit metadata only — it does not drive transfers or events.
 * Implemented on container block entities via mixin and persisted in the region stub.
 */
public interface CofferAccessRecord {

    /**
     * @return the game time of the most recent recorded slot change, or {@code -1} if none.
     */
    long coffer$accessTime();

    /**
     * @return the slot index of the most recent recorded change, or {@code -1} if none.
     */
    int coffer$accessSlot();

    /**
     * @return the kind of the most recent recorded change, or {@code null} if none.
     */
    @Nullable
    AccessType coffer$accessType();

    /**
     * Overwrites the record with the most recent change. Only the latest is kept.
     */
    void coffer$recordAccess(int slot, AccessType type, long gameTime);
}
