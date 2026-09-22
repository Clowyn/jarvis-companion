package com.alcyone.jarvis.building;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.entity.JarvisFakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton tick-distributed construction manager for Jarvis.
 * Executes BuildTask queues smoothly across server ticks without causing lag or TPS drops.
 * Jarvis physically navigates within reach (<= 4.5 blocks), aims, swings, and consumes blocks from his inventory.
 */
public class BuildManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");
    private static final BuildManager INSTANCE = new BuildManager();

    public static BuildManager getInstance() {
        return INSTANCE;
    }

    private BuildTask activeTask = null;
    private int tickCooldown = 0;
    private int notifyCooldown = 0;
    private int moveStuckTicks = 0;
    private BlockPos lastTargetPos = null;

    private BuildManager() {}

    /**
     * Assigns a new construction task, superseding or cancelling any previous running task.
     */
    public synchronized boolean startTask(BuildTask task) {
        if (this.activeTask != null && !this.activeTask.isCompleted() && !this.activeTask.isCancelled()) {
            this.activeTask.setCancelled(true);
        }
        this.activeTask = task;
        this.tickCooldown = 0;
        this.notifyCooldown = 0;
        this.moveStuckTicks = 0;
        this.lastTargetPos = null;
        LOGGER.info("[Jarvis] BuildManager started task '{}' ({} blocks)", task.getStructureType(), task.getTotalBlocks());
        return true;
    }

    public synchronized BuildTask getActiveTask() {
        return activeTask;
    }

    public synchronized boolean cancelTask() {
        if (this.activeTask != null && !this.activeTask.isCompleted() && !this.activeTask.isCancelled()) {
            this.activeTask.setCancelled(true);
            LOGGER.info("[Jarvis] BuildManager cancelled task '{}'", this.activeTask.getStructureType());
            return true;
        }
        return false;
    }

    public synchronized void clearTask() {
        this.activeTask = null;
    }

    /**
     * Ticks the building engine. Called directly from JarvisCompanionEntity.aiStep() on the main server thread.
     */
    public void tick(JarvisCompanionEntity companion) {
        BuildTask task;
        synchronized (this) {
            task = this.activeTask;
        }

        if (task == null || task.isCompleted() || task.isCancelled()) {
            return;
        }

        // Completion check
        if (!task.hasRemainingBlocks()) {
            task.setCompleted(true);
            onTaskCompleted(companion, task);
            return;
        }

        // Pacing: place at most 1 block every 2 ticks (10 blocks/sec for smooth non-lagging animation)
        if (--this.tickCooldown > 0) {
            return;
        }

        BlockPlacement placement = task.peekNext();
        if (placement == null) {
            task.setCompleted(true);
            onTaskCompleted(companion, task);
            return;
        }

        BlockPos targetPos = placement.getPos();
        ServerLevel level = (ServerLevel) companion.level();

        // 1. Distance & Reach check (survival reach ~4.5 blocks, 4.5^2 = 20.25)
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        double distSq = companion.position().distanceToSqr(targetCenter);

        if (distSq > 20.25D) {
            // Out of reach: navigate towards the target position
            companion.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.15D);

            if (targetPos.equals(lastTargetPos)) {
                moveStuckTicks++;
                if (moveStuckTicks > 80) { // Stuck trying to reach this block for 4 seconds
                    // Companion might be obstructed or cannot reach elevated spot. Skip this block.
                    LOGGER.warn("[Jarvis] BuildManager: Unreachable block at {}, skipping.", targetPos.toShortString());
                    task.pollNext();
                    task.incrementSkipped();
                    moveStuckTicks = 0;
                }
            } else {
                lastTargetPos = targetPos;
                moveStuckTicks = 0;
            }
            return; // Wait for navigation
        }

        // We are within reach: reset stuck counter
        moveStuckTicks = 0;
        lastTargetPos = null;

        // Look directly at the target placement coordinate
        companion.getLookControl().setLookAt(targetCenter.x, targetCenter.y, targetCenter.z);

        // 2. Check if block already occupied and cannot be replaced
        BlockState currentState = level.getBlockState(targetPos);
        if (!currentState.canBeReplaced()) {
            // Already occupied by a solid block
            task.pollNext();
            task.incrementSkipped();
            return;
        }

        // 3. Material Check & Inventory Consumption
        ItemStackHandler inventory = companion.getInventory();
        int slot = findMaterialSlot(inventory, placement);

        if (slot == -1) {
            // Jarvis doesn't have required block
            if (this.notifyCooldown-- <= 0) {
                this.notifyCooldown = 100; // Notify every 5 seconds
                notifyPlayer(companion, "[Jarvis] İnşaat için malzeme gerekiyor: " + placement.getBlockId() + " (Eksik blok)");
            }
            return; // Pause until player provides items
        }

        // Extract 1 item from inventory
        ItemStack extracted = inventory.extractItem(slot, 1, false);
        if (extracted.isEmpty()) {
            return;
        }

        String blockToPlace = BuiltInRegistries.ITEM.getKey(extracted.getItem()).toString();

        // 4. Physical Placement via FakePlayer
        JarvisFakePlayer.ExecutionResult result = JarvisFakePlayer.executePlaceBlock(companion, targetPos, blockToPlace);

        if (result.success()) {
            task.pollNext();
            task.incrementPlaced();
            companion.swing(InteractionHand.MAIN_HAND);
            this.tickCooldown = 2; // 2-tick cooldown
        } else {
            // If placement failed (e.g. unsupported torch/door), return item back to inventory
            ItemStack leftover = inventory.insertItem(slot, extracted, false);
            if (!leftover.isEmpty()) {
                companion.spawnAtLocation(leftover);
            }
            // Skip block to prevent infinite loop
            task.pollNext();
            task.incrementSkipped();
            this.tickCooldown = 2;
        }
    }

    /**
     * Finds slot index in companion's inventory containing the desired block or suitable fallback.
     */
    private int findMaterialSlot(ItemStackHandler inventory, BlockPlacement placement) {
        String targetId = placement.getBlockId();
        boolean isSpecial = placement.getPriority() >= 4; // Torches, doors, railings

        int fallbackSlot = -1;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (itemId.equalsIgnoreCase(targetId)) {
                return i;
            }

            // For general building blocks (priority 1, 2, 3), allow common construction fallbacks
            if (!isSpecial && fallbackSlot == -1) {
                if (itemId.contains("cobblestone") || itemId.contains("stone") ||
                    itemId.contains("planks") || itemId.contains("dirt") || itemId.contains("deepslate")) {
                    fallbackSlot = i;
                }
            }
        }

        return isSpecial ? -1 : fallbackSlot;
    }

    private void onTaskCompleted(JarvisCompanionEntity companion, BuildTask task) {
        try {
            companion.level().playSound(
                    null,
                    companion.getX(),
                    companion.getY(),
                    companion.getZ(),
                    SoundEvents.PLAYER_LEVELUP,
                    companion.getSoundSource(),
                    1.0F,
                    1.0F
            );
        } catch (Exception ignored) {}

        String msg = "[Jarvis] " + task.getStructureType().toUpperCase() + " inşası tamamlandı! (" +
                task.getPlacedBlocks() + " blok yerleştirildi" +
                (task.getSkippedBlocks() > 0 ? ", " + task.getSkippedBlocks() + " atlandı" : "") + ").";
        notifyPlayer(companion, msg);
        LOGGER.info(msg);
    }

    private void notifyPlayer(JarvisCompanionEntity companion, String message) {
        Component comp = Component.literal(message).withStyle(ChatFormatting.AQUA);
        Player follow = companion.getFollowTarget();
        if (follow instanceof ServerPlayer sp) {
            sp.sendSystemMessage(comp);
        } else if (companion.getServer() != null) {
            companion.getServer().getPlayerList().broadcastSystemMessage(comp, false);
        }
    }
}
