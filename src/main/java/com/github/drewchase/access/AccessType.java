package com.github.drewchase.access;

/**
 * The kind of the most recent slot mutation recorded for an inventory (Phase 5 access metadata).
 * Audit-only — independent of the dirty-event system.
 */
public enum AccessType {
    /** A previously empty slot received items. */
    INSERTED_INTO_EMPTY,
    /** A slot that held items became empty. */
    SLOT_EMPTIED,
    /** A slot's stack changed count (or contents) while remaining non-empty. */
    QUANTITY_CHANGED
}
