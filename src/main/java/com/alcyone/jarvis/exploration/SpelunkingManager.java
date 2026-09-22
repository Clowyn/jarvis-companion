package com.alcyone.jarvis.exploration;

import com.alcyone.jarvis.crafting.CraftingManager;
import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.entity.JarvisFakePlayer;
import com.alcyone.jarvis.storage.StorageManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Autonomous Cave Exploration, Lighting & Dungeon Raider Engine for Jarvis Companion.
 * Maps dark cave areas, systematically places torches on low-light floor blocks,
 * neutralizes monster spawners with light or breaks them, and loots dungeon chests.
 */
public class SpelunkingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    /**
     * Lights up dark areas by placing torches on floor blocks with block light <= 1.
     */
    public static JsonObject lightUpArea(ServerLevel level, JarvisCompanionEntity companion,
                                        BlockPos searchCenter, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int rad = Math.max(4, Math.min(radius, 32));
        BlockPos min = center.offset(-rad, -rad, -rad);
        BlockPos max = center.offset(rad, rad, rad);

        ItemStackHandler inv = companion.getInventory();
        int torchesPlaced = 0;
        JsonArray placedLocations = new JsonArray();
        List<BlockPos> torchPositions = new ArrayList<>();

        // Locate existing torches first to maintain spacing
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockState(pos).is(Blocks.TORCH) || level.getBlockState(pos).is(Blocks.WALL_TORCH)) {
                torchPositions.add(pos.immutable());
            }
        }

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;

            BlockState floor = level.getBlockState(pos);
            BlockPos above = pos.above();
            BlockState air = level.getBlockState(above);

            // Valid floor: solid top, air above, dark (block light <= 1)
            if (floor.isSolidRender(level, pos) && air.isAir()) {
                int blockLight = level.getBrightness(LightLayer.BLOCK, above);
                if (blockLight <= 1) {
                    // Check minimum spacing (at least 6 blocks from any torch)
                    boolean tooClose = false;
                    for (BlockPos t : torchPositions) {
                        if (t.distSqr(above) < 36) { // 6^2 = 36
                            tooClose = true;
                            break;
                        }
                    }

                    if (!tooClose) {
                        // Ensure we have a torch
                        if (!hasTorch(inv)) {
                            // Try auto-crafting torches
                            CraftingManager.craftItem(level, companion, "minecraft:torch", 16);
                        }

                        if (consumeTorch(inv)) {
                            level.setBlockAndUpdate(above, Blocks.TORCH.defaultBlockState());
                            level.playSound(null, above, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
                            torchPositions.add(above.immutable());
                            torchesPlaced++;

                            JsonObject pObj = new JsonObject();
                            pObj.addProperty("x", above.getX());
                            pObj.addProperty("y", above.getY());
                            pObj.addProperty("z", above.getZ());
                            placedLocations.add(pObj);
                        } else {
                            // No torches and cannot craft more
                            break;
                        }
                    }
                }
            }
        }

        result.addProperty("success", true);
        result.addProperty("torches_placed", torchesPlaced);
        result.add("locations", placedLocations);

        return result;
    }

    /**
     * Neutralizes or breaks monster spawners and collects loot from dungeon chests.
     */
    public static JsonObject neutralizeSpawners(ServerLevel level, JarvisCompanionEntity companion,
                                               BlockPos searchCenter, int radius, boolean breakSpawner) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int rad = Math.max(4, Math.min(radius, 32));
        BlockPos min = center.offset(-rad, -rad, -rad);
        BlockPos max = center.offset(rad, rad, rad);

        List<BlockPos> spawners = new ArrayList<>();
        List<BlockPos> dungeonChests = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);

            if (state.getBlock() instanceof SpawnerBlock) {
                spawners.add(pos.immutable());
            } else if (state.is(Blocks.CHEST)) {
                dungeonChests.add(pos.immutable());
            }
        }

        int spawnersNeutralized = 0;
        int spawnersBroken = 0;
        int chestsLooted = 0;
        ItemStackHandler inv = companion.getInventory();

        for (BlockPos spawnerPos : spawners) {
            if (breakSpawner) {
                JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, spawnerPos);
                if (breakRes.success()) {
                    spawnersBroken++;
                }
            } else {
                // Place torches on top and on 4 horizontal sides
                BlockPos top = spawnerPos.above();
                if (level.getBlockState(top).isAir() && consumeOrCraftTorch(level, companion, inv)) {
                    level.setBlockAndUpdate(top, Blocks.TORCH.defaultBlockState());
                }

                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = spawnerPos.relative(dir);
                    if (level.getBlockState(side).isAir() && consumeOrCraftTorch(level, companion, inv)) {
                        level.setBlockAndUpdate(side, Blocks.TORCH.defaultBlockState());
                    }
                }
                spawnersNeutralized++;
            }
        }

        // Loot nearby dungeon chests
        for (BlockPos chestPos : dungeonChests) {
            // Retrieve all items from dungeon chest into Jarvis's inventory
            JsonObject retRes = StorageManager.retrieveItem(level, chestPos, companion, null, "all", 64);
            if (retRes.has("success") && retRes.get("success").getAsBoolean()) {
                chestsLooted++;
            }
        }

        result.addProperty("success", true);
        result.addProperty("spawners_found", spawners.size());
        result.addProperty("spawners_broken", spawnersBroken);
        result.addProperty("spawners_neutralized", spawnersNeutralized);
        result.addProperty("chests_looted", chestsLooted);

        return result;
    }

    private static boolean hasTorch(ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.TORCH)) return true;
        }
        return false;
    }

    private static boolean consumeTorch(ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.is(Items.TORCH)) {
                inv.extractItem(i, 1, false);
                return true;
            }
        }
        return false;
    }

    private static boolean consumeOrCraftTorch(ServerLevel level, JarvisCompanionEntity companion, ItemStackHandler inv) {
        if (consumeTorch(inv)) return true;
        CraftingManager.craftItem(level, companion, "minecraft:torch", 16);
        return consumeTorch(inv);
    }
}
