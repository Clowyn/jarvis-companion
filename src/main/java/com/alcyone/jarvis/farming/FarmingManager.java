package com.alcyone.jarvis.farming;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.entity.JarvisFakePlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Autonomous Farming & Husbandry Engine for Jarvis Companion.
 * Handles crop detection, mature crop harvesting, instant replanting,
 * animal breeding (cows, sheep, pigs, chickens), and wool shearing.
 */
public class FarmingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    /**
     * Harvests all mature crops in the area and immediately replants matching seeds from inventory.
     */
    public static JsonObject harvestAndReplant(ServerLevel level, JarvisCompanionEntity companion,
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

        int harvestedCount = 0;
        int replantedCount = 0;
        JsonArray harvestedArr = new JsonArray();
        ItemStackHandler inv = companion.getInventory();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);

            // 1. Standard CropBlock (Wheat, Carrots, Potatoes, Beetroots)
            if (state.getBlock() instanceof CropBlock crop) {
                if (crop.isMaxAge(state)) {
                    // Check appropriate seed/crop item for replanting
                    ItemStack seedToReplant = getReplantSeed(state, inv);

                    // Break mature crop
                    JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, pos);
                    if (breakRes.success()) {
                        harvestedCount++;
                        JsonObject cObj = new JsonObject();
                        cObj.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        cObj.addProperty("x", pos.getX());
                        cObj.addProperty("y", pos.getY());
                        cObj.addProperty("z", pos.getZ());
                        harvestedArr.add(cObj);

                        // Replant immediately on the farmland
                        if (seedToReplant != null && !seedToReplant.isEmpty()) {
                            BlockState defaultCrop = crop.getStateForAge(0);
                            level.setBlockAndUpdate(pos, defaultCrop);
                            level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
                            replantedCount++;
                        }
                    }
                }
            }
            // 2. Sugar Cane (only harvest upper stalks, leaving the base stalk to regrow)
            else if (state.getBlock() instanceof SugarCaneBlock) {
                BlockState below = level.getBlockState(pos.below());
                if (below.getBlock() instanceof SugarCaneBlock) {
                    JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, pos);
                    if (breakRes.success()) {
                        harvestedCount++;
                        JsonObject cObj = new JsonObject();
                        cObj.addProperty("block", "minecraft:sugar_cane");
                        cObj.addProperty("x", pos.getX());
                        cObj.addProperty("y", pos.getY());
                        cObj.addProperty("z", pos.getZ());
                        harvestedArr.add(cObj);
                    }
                }
            }
            // 3. Melon & Pumpkin Blocks (break fruit without touching stem)
            else if (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)) {
                JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, pos);
                if (breakRes.success()) {
                    harvestedCount++;
                    JsonObject cObj = new JsonObject();
                    cObj.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    cObj.addProperty("x", pos.getX());
                    cObj.addProperty("y", pos.getY());
                    cObj.addProperty("z", pos.getZ());
                    harvestedArr.add(cObj);
                }
            }
            // 4. Nether Wart
            else if (state.getBlock() instanceof NetherWartBlock wart) {
                if (state.getValue(NetherWartBlock.AGE) >= 3) {
                    ItemStack seedToReplant = extractItemFromInventory(inv, Items.NETHER_WART, 1);
                    JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, pos);
                    if (breakRes.success()) {
                        harvestedCount++;
                        if (seedToReplant != null && !seedToReplant.isEmpty()) {
                            level.setBlockAndUpdate(pos, Blocks.NETHER_WART.defaultBlockState());
                            level.playSound(null, pos, SoundEvents.NETHER_WART_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
                            replantedCount++;
                        }
                    }
                }
            }
            // 5. Cocoa Beans
            else if (state.getBlock() instanceof CocoaBlock cocoa) {
                if (state.getValue(CocoaBlock.AGE) >= 2) {
                    Direction facing = state.getValue(CocoaBlock.FACING);
                    ItemStack seedToReplant = extractItemFromInventory(inv, Items.COCOA_BEANS, 1);
                    JarvisFakePlayer.ExecutionResult breakRes = JarvisFakePlayer.executeBreakBlock(companion, pos);
                    if (breakRes.success()) {
                        harvestedCount++;
                        if (seedToReplant != null && !seedToReplant.isEmpty()) {
                            level.setBlockAndUpdate(pos, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, facing));
                            level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
                            replantedCount++;
                        }
                    }
                }
            }
        }

        result.addProperty("success", true);
        result.addProperty("harvested_count", harvestedCount);
        result.addProperty("replanted_count", replantedCount);
        result.add("harvested_crops", harvestedArr);

        return result;
    }

    private static ItemStack getReplantSeed(BlockState cropState, ItemStackHandler inv) {
        if (cropState.is(Blocks.WHEAT)) {
            return extractItemFromInventory(inv, Items.WHEAT_SEEDS, 1);
        } else if (cropState.is(Blocks.CARROTS)) {
            return extractItemFromInventory(inv, Items.CARROT, 1);
        } else if (cropState.is(Blocks.POTATOES)) {
            return extractItemFromInventory(inv, Items.POTATO, 1);
        } else if (cropState.is(Blocks.BEETROOTS)) {
            return extractItemFromInventory(inv, Items.BEETROOT_SEEDS, 1);
        }
        return null;
    }

    private static ItemStack extractItemFromInventory(ItemStackHandler inv, net.minecraft.world.item.Item item, int amount) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.is(item)) {
                return inv.extractItem(i, amount, false);
            }
        }
        return null;
    }

    /**
     * Breeds nearby farm animals (Cows, Sheep, Pigs, Chickens) using food in companion inventory.
     */
    public static JsonObject breedAnimals(ServerLevel level, JarvisCompanionEntity companion,
                                          BlockPos searchCenter, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int rad = Math.max(4, Math.min(radius, 32));
        AABB box = new AABB(center).inflate(rad);
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, box,
                a -> a.isAlive() && !a.isBaby() && a.getAge() == 0 && !a.isInLove());

        int bredCount = 0;
        ItemStackHandler inv = companion.getInventory();
        JsonArray bredArr = new JsonArray();

        for (Animal animal : animals) {
            ItemStack food = null;
            if (animal instanceof Cow || animal instanceof Sheep) {
                food = extractItemFromInventory(inv, Items.WHEAT, 1);
            } else if (animal instanceof Pig) {
                food = extractItemFromInventory(inv, Items.CARROT, 1);
                if (food == null || food.isEmpty()) food = extractItemFromInventory(inv, Items.POTATO, 1);
                if (food == null || food.isEmpty()) food = extractItemFromInventory(inv, Items.BEETROOT, 1);
            } else if (animal instanceof Chicken) {
                food = extractItemFromInventory(inv, Items.WHEAT_SEEDS, 1);
                if (food == null || food.isEmpty()) food = extractItemFromInventory(inv, Items.MELON_SEEDS, 1);
                if (food == null || food.isEmpty()) food = extractItemFromInventory(inv, Items.PUMPKIN_SEEDS, 1);
                if (food == null || food.isEmpty()) food = extractItemFromInventory(inv, Items.BEETROOT_SEEDS, 1);
            }

            if (food != null && !food.isEmpty()) {
                animal.setInLove(null);
                level.sendParticles(ParticleTypes.HEART, animal.getX(), animal.getY() + 0.5D, animal.getZ(), 7, 0.3D, 0.3D, 0.3D, 0.05D);
                level.playSound(null, animal.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
                bredCount++;

                JsonObject bObj = new JsonObject();
                bObj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString());
                bObj.addProperty("id", animal.getId());
                bredArr.add(bObj);
            }
        }

        result.addProperty("success", true);
        result.addProperty("animals_bred", bredCount);
        result.add("bred_list", bredArr);

        return result;
    }

    /**
     * Shears all woolly sheep in the area using shears in companion inventory.
     */
    public static JsonObject shearSheep(ServerLevel level, JarvisCompanionEntity companion,
                                        BlockPos searchCenter, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        ItemStackHandler inv = companion.getInventory();
        int shearSlot = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.getItem() instanceof ShearsItem || stack.is(Items.SHEARS)) {
                shearSlot = i;
                break;
            }
        }

        if (shearSlot == -1) {
            result.addProperty("success", false);
            result.addProperty("error", "No shears found in companion inventory");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int rad = Math.max(4, Math.min(radius, 32));
        AABB box = new AABB(center).inflate(rad);
        List<Sheep> sheepList = level.getEntitiesOfClass(Sheep.class, box,
                s -> s.isAlive() && !s.isBaby() && s.readyForShearing());

        int shearedCount = 0;
        for (Sheep sheep : sheepList) {
            ItemStack shears = inv.getStackInSlot(shearSlot);
            if (shears.isEmpty()) break;

            sheep.shear(SoundSource.PLAYERS);
            shears.hurtAndBreak(1, companion, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            shearedCount++;

            // Passive pickup will collect the dropped wool into inventory
            level.playSound(null, sheep.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 1.0F);
        }

        result.addProperty("success", true);
        result.addProperty("sheep_sheared", shearedCount);

        return result;
    }

    /**
     * Executes all farming tasks in the area: harvesting/replanting crops, shearing sheep, and breeding animals.
     */
    public static JsonObject farmAll(ServerLevel level, JarvisCompanionEntity companion,
                                    BlockPos searchCenter, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        JsonObject cropsRes = harvestAndReplant(level, companion, searchCenter, radius);
        JsonObject sheepRes = shearSheep(level, companion, searchCenter, radius);
        JsonObject breedRes = breedAnimals(level, companion, searchCenter, radius);

        result.addProperty("success", true);
        result.add("crops", cropsRes);
        result.add("shearing", sheepRes);
        result.add("breeding", breedRes);

        int totalHarvested = cropsRes.has("harvested_count") ? cropsRes.get("harvested_count").getAsInt() : 0;
        int totalReplanted = cropsRes.has("replanted_count") ? cropsRes.get("replanted_count").getAsInt() : 0;
        int totalSheared = sheepRes.has("sheep_sheared") ? sheepRes.get("sheep_sheared").getAsInt() : 0;
        int totalBred = breedRes.has("animals_bred") ? breedRes.get("animals_bred").getAsInt() : 0;

        result.addProperty("total_crops_harvested", totalHarvested);
        result.addProperty("total_crops_replanted", totalReplanted);
        result.addProperty("total_sheep_sheared", totalSheared);
        result.addProperty("total_animals_bred", totalBred);

        return result;
    }
}

