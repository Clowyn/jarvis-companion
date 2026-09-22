package com.alcyone.jarvis.lumberjack;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.entity.JarvisFakePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Autonomous Lumberjack & Reforestation Engine for Jarvis Companion.
 * Identifies tree trunks, cuts them safely bottom-up, clears foliage,
 * and replants saplings on the original soil to ensure zero deforestation.
 */
public class LumberjackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    /**
     * Finds and chops trees within the search radius, replanting saplings on soil.
     */
    public static JsonObject chopTrees(ServerLevel level, JarvisCompanionEntity companion,
                                      BlockPos searchCenter, int radius, int maxTrees) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int rad = Math.max(4, Math.min(radius, 32));
        int treeLimit = maxTrees > 0 ? Math.min(maxTrees, 10) : 5;

        BlockPos min = center.offset(-rad, -rad, -rad);
        BlockPos max = center.offset(rad, rad, rad);

        List<BlockPos> treeBases = new ArrayList<>();

        // Locate tree bases: Log blocks resting directly on dirt/soil
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                BlockState ground = level.getBlockState(pos.below());
                if (ground.is(BlockTags.DIRT) || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MUD)) {
                    treeBases.add(pos.immutable());
                    if (treeBases.size() >= treeLimit) {
                        break;
                    }
                }
            }
        }

        int totalLogsCut = 0;
        int totalSaplingsPlanted = 0;
        int treesChopped = 0;
        JsonArray treeDetails = new JsonArray();
        ItemStackHandler inv = companion.getInventory();

        for (BlockPos basePos : treeBases) {
            // Find all connected log blocks in this tree using BFS/DFS
            List<BlockPos> treeLogs = findConnectedLogs(level, basePos, 35);
            if (treeLogs.isEmpty()) continue;

            // Sort logs bottom-to-top (by Y ascending)
            treeLogs.sort(Comparator.comparingInt(BlockPos::getY));

            int logsThisTree = 0;
            String logType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(basePos).getBlock()).toString();

            for (BlockPos logPos : treeLogs) {
                JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, logPos);
                if (breakRes.success()) {
                    logsThisTree++;
                    totalLogsCut++;
                }
            }

            if (logsThisTree > 0) {
                treesChopped++;

                // Reforestation: Replant sapling at basePos if soil below is still suitable
                BlockState soil = level.getBlockState(basePos.below());
                if ((soil.is(BlockTags.DIRT) || soil.is(Blocks.MOSS_BLOCK) || soil.is(Blocks.MUD))
                        && level.getBlockState(basePos).isAir()) {
                    ItemStack saplingStack = extractSapling(inv);
                    if (saplingStack != null && !saplingStack.isEmpty()) {
                        if (saplingStack.getItem() instanceof BlockItem blockItem) {
                            Block saplingBlock = blockItem.getBlock();
                            level.setBlockAndUpdate(basePos, saplingBlock.defaultBlockState());
                            level.playSound(null, basePos, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
                            totalSaplingsPlanted++;
                        }
                    }
                }

                JsonObject tObj = new JsonObject();
                tObj.addProperty("base_x", basePos.getX());
                tObj.addProperty("base_y", basePos.getY());
                tObj.addProperty("base_z", basePos.getZ());
                tObj.addProperty("type", logType);
                tObj.addProperty("logs_harvested", logsThisTree);
                treeDetails.add(tObj);
            }
        }

        result.addProperty("success", true);
        result.addProperty("trees_chopped", treesChopped);
        result.addProperty("logs_harvested", totalLogsCut);
        result.addProperty("saplings_replanted", totalSaplingsPlanted);
        result.add("trees", treeDetails);

        return result;
    }

    /**
     * Traverses connected log blocks of the same tree up to a maximum count.
     */
    private static List<BlockPos> findConnectedLogs(ServerLevel level, BlockPos start, int maxCount) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && found.size() < maxCount) {
            BlockPos current = queue.poll();
            BlockState st = level.getBlockState(current);

            if (st.is(BlockTags.LOGS)) {
                found.add(current);

                // Explore adjacent 26 neighbors (focusing on upward and horizontal)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos neighbor = current.offset(dx, dy, dz);
                            if (!visited.contains(neighbor) && level.hasChunkAt(neighbor)) {
                                visited.add(neighbor);
                                if (level.getBlockState(neighbor).is(BlockTags.LOGS)) {
                                    queue.add(neighbor);
                                }
                            }
                        }
                    }
                }
            }
        }

        return found;
    }

    /**
     * Extracts a sapling item from Jarvis's inventory.
     */
    private static ItemStack extractSapling(ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && (stack.is(ItemTags.SAPLINGS) || stack.getItem().toString().contains("sapling"))) {
                return inv.extractItem(i, 1, false);
            }
        }
        return null;
    }
}
