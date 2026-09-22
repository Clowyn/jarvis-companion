package com.alcyone.jarvis.building;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Represents an autonomous construction task queued for execution.
 * Maintains state, block placement queue, progress tracking, and statistics.
 */
public class BuildTask {

    private final String taskId;
    private final String structureType;
    private final BlockPos origin;
    private final String preferredMaterial;
    private final Deque<BlockPlacement> blockQueue;
    private final int totalBlocks;

    private int placedBlocks = 0;
    private int skippedBlocks = 0;
    private boolean completed = false;
    private boolean cancelled = false;
    private String statusMessage = "In queue";
    private final long startTime;
    private long lastPlacementTime = 0;

    public BuildTask(String structureType, BlockPos origin, String preferredMaterial, List<BlockPlacement> blocks) {
        this.taskId = "build-" + UUID.randomUUID().toString().substring(0, 8);
        this.structureType = structureType != null ? structureType : "custom";
        this.origin = origin;
        this.preferredMaterial = preferredMaterial != null ? preferredMaterial : "minecraft:cobblestone";

        // Sort blocks by structural priority and bottom-to-top Y
        Collections.sort(blocks);
        this.blockQueue = new ArrayDeque<>(blocks);
        this.totalBlocks = blocks.size();
        this.startTime = System.currentTimeMillis();
        this.statusMessage = "Building " + this.structureType + " (" + this.totalBlocks + " blocks)";
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStructureType() {
        return structureType;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public String getPreferredMaterial() {
        return preferredMaterial;
    }

    public synchronized BlockPlacement peekNext() {
        return blockQueue.peekFirst();
    }

    public synchronized BlockPlacement pollNext() {
        return blockQueue.pollFirst();
    }

    public synchronized boolean hasRemainingBlocks() {
        return !blockQueue.isEmpty();
    }

    public synchronized int getRemainingCount() {
        return blockQueue.size();
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public synchronized int getPlacedBlocks() {
        return placedBlocks;
    }

    public synchronized void incrementPlaced() {
        this.placedBlocks++;
        this.lastPlacementTime = System.currentTimeMillis();
    }

    public synchronized int getSkippedBlocks() {
        return skippedBlocks;
    }

    public synchronized void incrementSkipped() {
        this.skippedBlocks++;
    }

    public synchronized boolean isCompleted() {
        return completed;
    }

    public synchronized void setCompleted(boolean completed) {
        this.completed = completed;
        if (completed) {
            this.statusMessage = "Completed (" + this.placedBlocks + " blocks placed)";
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
        if (cancelled) {
            this.statusMessage = "Cancelled";
        }
    }

    public synchronized String getStatusMessage() {
        return statusMessage;
    }

    public synchronized void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getLastPlacementTime() {
        return lastPlacementTime;
    }

    public synchronized double getProgress() {
        if (totalBlocks == 0) return 1.0;
        return (double) (placedBlocks + skippedBlocks) / totalBlocks;
    }

    public synchronized JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("task_id", taskId);
        json.addProperty("structure_type", structureType);
        json.addProperty("total_blocks", totalBlocks);
        json.addProperty("placed_blocks", placedBlocks);
        json.addProperty("skipped_blocks", skippedBlocks);
        json.addProperty("remaining_blocks", blockQueue.size());
        json.addProperty("progress", Math.round(getProgress() * 1000.0) / 1000.0);
        json.addProperty("completed", completed);
        json.addProperty("cancelled", cancelled);
        json.addProperty("status", statusMessage);

        if (origin != null) {
            JsonObject origObj = new JsonObject();
            origObj.addProperty("x", origin.getX());
            origObj.addProperty("y", origin.getY());
            origObj.addProperty("z", origin.getZ());
            json.add("origin", origObj);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        json.addProperty("elapsed_seconds", Math.round(elapsedMs / 100.0) / 10.0);

        return json;
    }
}
