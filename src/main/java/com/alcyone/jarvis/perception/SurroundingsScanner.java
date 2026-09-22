package com.alcyone.jarvis.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-performance, thread-safe surroundings perception engine for the Jarvis Companion mod.
 * Features 3-stage pruning:
 * 1. Chunk Section Air Pruning: O(1) skipping of empty 16x16x16 sections via LevelChunkSection.hasOnlyAir().
 * 2. Block Entity Fast-Path: Direct inspection of chunk block entities for containers and workstations.
 * 3. Tag-Based Classification: NeoForge common tags and vanilla tags (ores, containers, workstations, hazards).
 * Entity Scanning: Classifies players, monsters, animals, and item drops with detailed ItemStack metadata.
 * Perception Cache: 500ms TTL in-memory cache to prevent redundant world scans under rapid polling.
 */
public class SurroundingsScanner {

    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 128;
    public static final int DEFAULT_RADIUS = 16;

    /**
     * Clamps a requested radius between MIN_RADIUS (2) and MAX_RADIUS (128).
     */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(radius, MAX_RADIUS));
    }

    /**
     * Scans surroundings for a player, consulting the 500ms perception cache first.
     */
    public static JsonObject getOrScanPlayer(MinecraftServer server, ServerPlayer player, int radius) {
        int clampedRadius = clampRadius(radius);
        String cacheKey = player.getStringUUID() + ":" + clampedRadius;
        JsonObject cached = PerceptionCache.get(cacheKey, player.getX(), player.getY(), player.getZ(), clampedRadius);
        if (cached != null) {
            return cached;
        }

        JsonObject scanned = scan(player.serverLevel(), player.getX(), player.getY(), player.getZ(), clampedRadius, player);
        PerceptionCache.put(cacheKey, player.getX(), player.getY(), player.getZ(), clampedRadius, scanned);
        return scanned;
    }

    /**
     * Executes a full 3-stage pruned perception scan around given coordinates.
     */
    public static JsonObject scan(ServerLevel level, double originX, double originY, double originZ, int radius, Entity originEntity) {
        int clampedRadius = clampRadius(radius);
        double maxDistSq = (double) clampedRadius * clampedRadius;

        int minX = (int) Math.floor(originX - clampedRadius);
        int maxX = (int) Math.floor(originX + clampedRadius);
        int minY = Math.max((int) Math.floor(originY - clampedRadius), level.getMinBuildHeight());
        int maxY = Math.min((int) Math.floor(originY + clampedRadius), level.getMaxBuildHeight() - 1);
        int minZ = (int) Math.floor(originZ - clampedRadius);
        int maxZ = (int) Math.floor(originZ + clampedRadius);

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        int minSecY = minY >> 4;
        int maxSecY = maxY >> 4;

        Set<BlockPos> processedPositions = new HashSet<>();
        List<JsonObject> blocksList = new ArrayList<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }

                // Stage 2: Block Entity Fast-Path
                // Direct lookup of containers and workstations from chunk block entities
                for (Map.Entry<BlockPos, BlockEntity> beEntry : chunk.getBlockEntities().entrySet()) {
                    BlockPos bePos = beEntry.getKey();
                    if (bePos.getX() >= minX && bePos.getX() <= maxX
                            && bePos.getY() >= minY && bePos.getY() <= maxY
                            && bePos.getZ() >= minZ && bePos.getZ() <= maxZ) {

                        double distSq = bePos.distToCenterSqr(originX, originY, originZ);
                        if (distSq <= maxDistSq) {
                            processedPositions.add(bePos.immutable());
                            BlockState beState = beEntry.getValue().getBlockState();
                            if (beState.isAir()) {
                                continue;
                            }
                            Block block = beState.getBlock();
                            if (BlockClassifier.isNaturalFiller(block, beState)) {
                                continue;
                            }

                            BlockCategory cat = BlockClassifier.classify(beState);
                            if (cat == BlockCategory.NONE) {
                                continue;
                            }
                            if (cat == BlockCategory.OTHER) {
                                cat = BlockCategory.CONTAINER;
                            }

                            double dist = Math.round(Math.sqrt(distSq) * 10.0) / 10.0;
                            blocksList.add(serializeBlock(bePos, block, beState, cat, dist));
                        }
                    }
                }

                // Stage 1 & 3: Chunk Section Air Pruning & Tag-Based Classification
                for (int secY = minSecY; secY <= maxSecY; secY++) {
                    int secIndex = chunk.getSectionIndexFromSectionY(secY);
                    if (secIndex < 0 || secIndex >= chunk.getSections().length) {
                        continue;
                    }

                    LevelChunkSection section = chunk.getSection(secIndex);
                    // Stage 1: Skip empty 16x16x16 sections in O(1)
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }

                    int startX = Math.max(minX, cx << 4);
                    int endX = Math.min(maxX, (cx << 4) + 15);
                    int startY = Math.max(minY, secY << 4);
                    int endY = Math.min(maxY, (secY << 4) + 15);
                    int startZ = Math.max(minZ, cz << 4);
                    int endZ = Math.min(maxZ, (cz << 4) + 15);

                    BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

                    for (int y = startY; y <= endY; y++) {
                        int localY = y & 15;
                        for (int x = startX; x <= endX; x++) {
                            int localX = x & 15;
                            for (int z = startZ; z <= endZ; z++) {
                                int localZ = z & 15;

                                mutablePos.set(x, y, z);
                                if (processedPositions.contains(mutablePos)) {
                                    continue;
                                }

                                double distSq = mutablePos.distToCenterSqr(originX, originY, originZ);
                                if (distSq > maxDistSq) {
                                    continue;
                                }

                                BlockState state = section.getBlockState(localX, localY, localZ);
                                if (state.isAir()) {
                                    continue;
                                }

                                Block block = state.getBlock();
                                if (BlockClassifier.isNaturalFiller(block, state)) {
                                    continue;
                                }

                                // Stage 3: Tag-based classification
                                BlockCategory cat = BlockClassifier.classify(state);
                                if (cat == BlockCategory.NONE) {
                                    continue;
                                }

                                double dist = Math.round(Math.sqrt(distSq) * 10.0) / 10.0;
                                blocksList.add(serializeBlock(mutablePos.immutable(), block, state, cat, dist));
                            }
                        }
                    }
                }
            }
        }

        // Proximity sorting (closest first)
        blocksList.sort(Comparator.comparingDouble(b -> b.get("distance").getAsDouble()));

        JsonArray blocksArray = new JsonArray();
        for (JsonObject b : blocksList) {
            blocksArray.add(b);
        }

        // Entity Scanning
        AABB box = new AABB(
                originX - clampedRadius, Math.max(originY - clampedRadius, level.getMinBuildHeight()), originZ - clampedRadius,
                originX + clampedRadius, Math.min(originY + clampedRadius, level.getMaxBuildHeight()), originZ + clampedRadius
        );
        List<Entity> entities = level.getEntities(originEntity, box, Entity::isAlive);
        List<JsonObject> entitiesList = new ArrayList<>();

        for (Entity e : entities) {
            double distSq = e.distanceToSqr(originX, originY, originZ);
            if (distSq > maxDistSq) {
                continue;
            }
            double dist = Math.round(Math.sqrt(distSq) * 10.0) / 10.0;
            entitiesList.add(serializeEntity(e, dist));
        }

        // Sort entities by distance ascending
        entitiesList.sort(Comparator.comparingDouble(e -> e.get("distance").getAsDouble()));

        JsonArray entitiesArray = new JsonArray();
        for (JsonObject e : entitiesList) {
            entitiesArray.add(e);
        }

        JsonObject root = new JsonObject();
        JsonObject origin = new JsonObject();
        origin.addProperty("x", originX);
        origin.addProperty("y", originY);
        origin.addProperty("z", originZ);
        root.add("origin", origin);
        root.addProperty("radius", clampedRadius);
        root.add("blocks", blocksArray);
        root.add("entities", entitiesArray);

        return root;
    }

    private static JsonObject serializeBlock(BlockPos pos, Block block, BlockState state, BlockCategory category, double distance) {
        String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
        JsonObject b = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        b.add("pos", posObj);
        b.addProperty("x", pos.getX());
        b.addProperty("y", pos.getY());
        b.addProperty("z", pos.getZ());
        b.addProperty("block", blockId);
        b.addProperty("id", blockId);
        b.addProperty("type", category.getSerializedName());
        b.addProperty("category", category.getSerializedName());
        b.addProperty("distance", distance);
        return b;
    }

    private static JsonObject serializeEntity(Entity entity, double distance) {
        JsonObject ent = new JsonObject();
        ent.addProperty("id", entity.getId());
        ent.addProperty("uuid", entity.getStringUUID());
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        ent.addProperty("type", typeId);
        ent.addProperty("name", entity.getName().getString());

        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", entity.getX());
        posObj.addProperty("y", entity.getY());
        posObj.addProperty("z", entity.getZ());
        ent.add("pos", posObj);
        ent.addProperty("x", entity.getX());
        ent.addProperty("y", entity.getY());
        ent.addProperty("z", entity.getZ());
        ent.addProperty("distance", distance);

        String category = "other";
        if (entity instanceof Player) {
            category = "player";
        } else if (entity instanceof Monster || entity instanceof Enemy) {
            category = "monster";
        } else if (entity instanceof Animal || entity instanceof AmbientCreature || entity instanceof WaterAnimal || entity instanceof Villager) {
            category = "animal";
        } else if (entity instanceof ItemEntity itemEntity) {
            category = "item";
            ItemStack stack = itemEntity.getItem();
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int count = stack.getCount();
            String itemName = stack.getHoverName().getString();
            ent.addProperty("item", itemId);
            ent.addProperty("item_id", itemId);
            ent.addProperty("count", count);
            ent.addProperty("item_name", itemName);
            ent.addProperty("name", itemName);
        }
        ent.addProperty("category", category);

        if (entity instanceof LivingEntity living) {
            ent.addProperty("health", (double) Math.round(living.getHealth() * 10.0) / 10.0);
            ent.addProperty("max_health", (double) Math.round(living.getMaxHealth() * 10.0) / 10.0);
        }

        return ent;
    }
}
