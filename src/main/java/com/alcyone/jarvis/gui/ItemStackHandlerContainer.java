package com.alcyone.jarvis.gui;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

public class ItemStackHandlerContainer implements Container {
    private final ItemStackHandler handler;
    private final JarvisCompanionEntity companion;

    public ItemStackHandlerContainer(ItemStackHandler handler, JarvisCompanionEntity companion) {
        this.handler = handler;
        this.companion = companion;
    }

    @Override
    public int getContainerSize() {
        return handler.getSlots();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= handler.getSlots()) return ItemStack.EMPTY;
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= handler.getSlots() || amount <= 0) return ItemStack.EMPTY;
        ItemStack extracted = handler.extractItem(slot, amount, false);
        setChanged();
        return extracted;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= handler.getSlots()) return ItemStack.EMPTY;
        ItemStack current = handler.getStackInSlot(slot);
        handler.setStackInSlot(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < handler.getSlots()) {
            handler.setStackInSlot(slot, stack);
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        if (companion != null && companion.isAlive()) {
            companion.autoEquip();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return companion != null && companion.isAlive() && player.distanceToSqr(companion) <= 64.0D;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, ItemStack.EMPTY);
        }
        setChanged();
    }
}
