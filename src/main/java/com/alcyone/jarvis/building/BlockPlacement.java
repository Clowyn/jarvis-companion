package com.alcyone.jarvis.building;

import net.minecraft.core.BlockPos;

/**
 * Represents a single block to be placed as part of a BuildTask.
 * Implements Comparable to guarantee that lower blocks and foundations
 * are placed before upper walls, roofs, or fragile attachments (torches/doors).
 */
public class BlockPlacement implements Comparable<BlockPlacement> {

    private final BlockPos pos;
    private final String blockId;
    private final int priority; // 1: foundation/floor, 2: walls, 3: roof/ceiling, 4: details/torches

    public BlockPlacement(BlockPos pos, String blockId, int priority) {
        this.pos = pos;
        this.blockId = blockId != null ? blockId : "minecraft:cobblestone";
        this.priority = priority;
    }

    public BlockPlacement(BlockPos pos, String blockId) {
        this(pos, blockId, 2);
    }

    public BlockPos getPos() {
        return pos;
    }

    public String getBlockId() {
        return blockId;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(BlockPlacement other) {
        // Priority 1 comes before Priority 2
        int pCmp = Integer.compare(this.priority, other.priority);
        if (pCmp != 0) return pCmp;

        // Bottom-to-top Y order for structural integrity
        int yCmp = Integer.compare(this.pos.getY(), other.pos.getY());
        if (yCmp != 0) return yCmp;

        // Tie-breaker by X then Z
        int xCmp = Integer.compare(this.pos.getX(), other.pos.getX());
        if (xCmp != 0) return xCmp;
        return Integer.compare(this.pos.getZ(), other.pos.getZ());
    }

    @Override
    public String toString() {
        return "BlockPlacement{" +
                "pos=" + pos.toShortString() +
                ", blockId='" + blockId + '\'' +
                ", priority=" + priority +
                '}';
    }
}
