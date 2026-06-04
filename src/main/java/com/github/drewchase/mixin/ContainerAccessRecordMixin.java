package com.github.drewchase.mixin;

import com.github.drewchase.access.AccessType;
import com.github.drewchase.access.CofferAccessRecord;
import com.github.drewchase.storage.CofferInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 5: records the single most-recent slot mutation for each container — {game time, slot,
 * action type} — and persists it in the region stub. Audit metadata only; it does not affect
 * transfers or events.
 *
 * <p>The record is captured at the HEAD of the slot-mutating accessors, reading the pre-change stack
 * to classify the action. The container is hydrated first (Phase 2 lazy load) so the comparison sees
 * real contents rather than empty placeholders.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerAccessRecordMixin implements CofferAccessRecord {

    @Unique
    private static final String COFFER_ACCESS_TIME_KEY = "coffer:access_time";
    @Unique
    private static final String COFFER_ACCESS_SLOT_KEY = "coffer:access_slot";
    @Unique
    private static final String COFFER_ACCESS_TYPE_KEY = "coffer:access_type";

    @Shadow
    public abstract ItemStack getItem(int slot);

    @Unique
    private long coffer$accessTime = -1L;
    @Unique
    private int coffer$accessSlot = -1;
    @Unique
    @Nullable
    private AccessType coffer$accessType;

    @Override
    public long coffer$accessTime() {
        return this.coffer$accessTime;
    }

    @Override
    public int coffer$accessSlot() {
        return this.coffer$accessSlot;
    }

    @Override
    @Nullable
    public AccessType coffer$accessType() {
        return this.coffer$accessType;
    }

    @Override
    public void coffer$recordAccess(int slot, AccessType type, long gameTime) {
        this.coffer$accessSlot = slot;
        this.coffer$accessType = type;
        this.coffer$accessTime = gameTime;
    }

    @Unique
    private void coffer$record(int slot, @Nullable AccessType type) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (type == null || level == null) {
            return;
        }
        this.coffer$recordAccess(slot, type, level.getGameTime());
    }

    @Inject(method = "setItem", at = @At("HEAD"))
    private void coffer$recordSetItem(int slot, ItemStack newStack, CallbackInfo ci) {
        ((CofferInventory) this).coffer$ensureLoaded();
        ItemStack old = this.getItem(slot);
        this.coffer$record(slot, coffer$classify(old, newStack));
    }

    @Inject(method = "removeItem", at = @At("HEAD"))
    private void coffer$recordRemoveItem(int slot, int count, CallbackInfoReturnable<ItemStack> cir) {
        ((CofferInventory) this).coffer$ensureLoaded();
        ItemStack old = this.getItem(slot);
        if (old.isEmpty() || count <= 0) {
            return;
        }
        AccessType type = count >= old.getCount() ? AccessType.SLOT_EMPTIED : AccessType.QUANTITY_CHANGED;
        this.coffer$record(slot, type);
    }

    @Inject(method = "removeItemNoUpdate", at = @At("HEAD"))
    private void coffer$recordRemoveNoUpdate(int slot, CallbackInfoReturnable<ItemStack> cir) {
        ((CofferInventory) this).coffer$ensureLoaded();
        if (!this.getItem(slot).isEmpty()) {
            this.coffer$record(slot, AccessType.SLOT_EMPTIED);
        }
    }

    @Unique
    @Nullable
    private static AccessType coffer$classify(ItemStack oldStack, ItemStack newStack) {
        boolean oldEmpty = oldStack.isEmpty();
        boolean newEmpty = newStack.isEmpty();
        if (oldEmpty && !newEmpty) {
            return AccessType.INSERTED_INTO_EMPTY;
        }
        if (!oldEmpty && newEmpty) {
            return AccessType.SLOT_EMPTIED;
        }
        if (!oldEmpty && (oldStack.getCount() != newStack.getCount() || !ItemStack.isSameItemSameComponents(oldStack, newStack))) {
            return AccessType.QUANTITY_CHANGED;
        }
        return null;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void coffer$writeAccessRecord(ValueOutput output, CallbackInfo ci) {
        if (this.coffer$accessType != null) {
            output.putLong(COFFER_ACCESS_TIME_KEY, this.coffer$accessTime);
            output.putInt(COFFER_ACCESS_SLOT_KEY, this.coffer$accessSlot);
            output.putString(COFFER_ACCESS_TYPE_KEY, this.coffer$accessType.name());
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void coffer$readAccessRecord(ValueInput input, CallbackInfo ci) {
        input.getString(COFFER_ACCESS_TYPE_KEY).ifPresent(name -> {
            try {
                this.coffer$accessType = AccessType.valueOf(name);
                this.coffer$accessTime = input.getLongOr(COFFER_ACCESS_TIME_KEY, -1L);
                this.coffer$accessSlot = input.getIntOr(COFFER_ACCESS_SLOT_KEY, -1);
            } catch (IllegalArgumentException ignored) {
                // Unknown enum constant from a future/older version — leave the record empty.
            }
        });
    }
}
