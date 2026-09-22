package com.alcyone.jarvis.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural architecture and schematic structure generators for the Jarvis Companion.
 * Generates sorted BlockPlacement lists for Bridges, Emergency Shelters, Walls, and Platforms.
 */
public class StructureGenerators {

    /**
     * Generates a walkable bridge extending forward in direction from start.
     * Floor is laid at start.below() so the companion and player walk on top of it.
     */
    public static BuildTask createBridge(
            ServerLevel level,
            BlockPos start,
            Direction direction,
            int length,
            int width,
            String blockId,
            boolean railing,
            boolean torches
    ) {
        int actualLength = Math.max(3, Math.min(length, 64));
        int actualWidth = Math.max(1, Math.min(width, 7));
        if (actualWidth % 2 == 0) actualWidth++; // Ensure odd width for symmetric centering

        String material = (blockId != null && !blockId.isBlank()) ? blockId : "minecraft:cobblestone";
        String railingMaterial = material.contains("plank") || material.contains("wood")
                ? "minecraft:oak_fence" : "minecraft:cobblestone_wall";

        List<BlockPlacement> placements = new ArrayList<>();
        Direction perp = direction.getClockWise();
        int halfWidth = actualWidth / 2;

        for (int step = 0; step < actualLength; step++) {
            BlockPos centerFloor = start.relative(direction, step).below();

            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos floorPos = centerFloor.relative(perp, w);
                // Foundation / Walkway floor (Priority 1)
                placements.add(new BlockPlacement(floorPos, material, 1));

                // Railings on the outer edges (Priority 2)
                boolean isEdge = (Math.abs(w) == halfWidth) && (actualWidth > 1);
                if (railing && isEdge) {
                    BlockPos railPos = floorPos.above();
                    placements.add(new BlockPlacement(railPos, railingMaterial, 2));

                    // Periodic torches along the railing (Priority 4)
                    if (torches && (step % 6 == 0)) {
                        BlockPos torchPos = railPos.above();
                        placements.add(new BlockPlacement(torchPos, "minecraft:torch", 4));
                    }
                }
            }
        }

        return new BuildTask("bridge", start, material, placements);
    }

    /**
     * Generates a self-contained, lighted emergency bunker/shelter.
     * Standard 5x5 exterior (3x3 interior) with floor, 4 walls, doorway, ceiling, and torches.
     */
    public static BuildTask createShelter(
            ServerLevel level,
            BlockPos center,
            int rawSize,
            int rawHeight,
            String wallBlock,
            String floorBlock,
            String roofBlock,
            boolean addDoor,
            boolean addTorches
    ) {
        int size = Math.max(3, Math.min(rawSize, 9));
        if (size % 2 == 0) size++; // Ensure odd size for centered doorway
        int height = Math.max(3, Math.min(rawHeight, 6));

        int radius = size / 2;
        String wallMat = (wallBlock != null && !wallBlock.isBlank()) ? wallBlock : "minecraft:cobblestone";
        String floorMat = (floorBlock != null && !floorBlock.isBlank()) ? floorBlock : "minecraft:oak_planks";
        String roofMat = (roofBlock != null && !roofBlock.isBlank()) ? roofBlock : "minecraft:cobblestone";

        List<BlockPlacement> placements = new ArrayList<>();
        int baseY = center.getY();

        // 1. Foundation & Floor (Priority 1)
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos fPos = center.offset(dx, -1, dz);
                placements.add(new BlockPlacement(fPos, floorMat, 1));
            }
        }

        // 2. Perimeter Walls with Doorway (Priority 2)
        // Doorway placed at dz == radius (facing South) at center (dx == 0)
        for (int y = 0; y < height; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean isPerimeter = (dx == -radius || dx == radius || dz == -radius || dz == radius);
                    if (!isPerimeter) continue;

                    // Doorway opening: 1 block wide, 2 blocks high
                    boolean isDoorway = (dz == radius && dx == 0 && y < 2);
                    if (isDoorway) continue;

                    BlockPos wPos = center.offset(dx, y, dz);
                    placements.add(new BlockPlacement(wPos, wallMat, 2));
                }
            }
        }

        // 3. Roof / Ceiling (Priority 3)
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos rPos = center.offset(dx, height, dz);
                placements.add(new BlockPlacement(rPos, roofMat, 3));
            }
        }

        // 4. Interior Lighting (Priority 4)
        if (addTorches && radius >= 1) {
            int torchY = Math.min(2, height - 1);
            // 4 interior torches on inside wall faces
            placements.add(new BlockPlacement(center.offset(0, torchY, -(radius - 1)), "minecraft:torch", 4));
            placements.add(new BlockPlacement(center.offset(0, torchY, (radius - 1)), "minecraft:torch", 4));
            placements.add(new BlockPlacement(center.offset(-(radius - 1), torchY, 0), "minecraft:torch", 4));
            placements.add(new BlockPlacement(center.offset((radius - 1), torchY, 0), "minecraft:torch", 4));
        }

        // 5. Door (Priority 4)
        if (addDoor) {
            BlockPos doorPos = center.offset(0, 0, radius);
            placements.add(new BlockPlacement(doorPos, "minecraft:oak_door", 4));
        }

        return new BuildTask("shelter", center, wallMat, placements);
    }

    /**
     * Generates a defensive barricade wall perpendicular to facing or along a line.
     */
    public static BuildTask createWall(
            ServerLevel level,
            BlockPos center,
            Direction direction,
            int length,
            int height,
            String blockId,
            boolean crenellations
    ) {
        int actualLength = Math.max(3, Math.min(length, 48));
        if (actualLength % 2 == 0) actualLength++;
        int actualHeight = Math.max(2, Math.min(height, 8));

        String material = (blockId != null && !blockId.isBlank()) ? blockId : "minecraft:cobblestone";
        List<BlockPlacement> placements = new ArrayList<>();

        // Wall runs perpendicular to facing direction so it acts as an immediate barricade
        Direction wallRunDir = direction.getClockWise();
        int halfLen = actualLength / 2;

        for (int l = -halfLen; l <= halfLen; l++) {
            BlockPos baseColumn = center.relative(wallRunDir, l);

            for (int y = 0; y < actualHeight; y++) {
                BlockPos pos = baseColumn.above(y);
                placements.add(new BlockPlacement(pos, material, 2));
            }

            // Top battlements / crenellations
            if (crenellations && (Math.abs(l) % 2 == 0)) {
                BlockPos crenPos = baseColumn.above(actualHeight);
                placements.add(new BlockPlacement(crenPos, material, 3));
            }
        }

        return new BuildTask("wall", center, material, placements);
    }

    /**
     * Generates a flat platform foundation.
     */
    public static BuildTask createPlatform(
            ServerLevel level,
            BlockPos center,
            int sizeX,
            int sizeZ,
            String blockId
    ) {
        int sx = Math.max(2, Math.min(sizeX, 32));
        int sz = Math.max(2, Math.min(sizeZ, 32));
        String material = (blockId != null && !blockId.isBlank()) ? blockId : "minecraft:cobblestone";

        List<BlockPlacement> placements = new ArrayList<>();
        int hx = sx / 2;
        int hz = sz / 2;

        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                BlockPos pos = center.offset(dx, -1, dz);
                placements.add(new BlockPlacement(pos, material, 1));
            }
        }

        return new BuildTask("platform", center, material, placements);
    }
}
