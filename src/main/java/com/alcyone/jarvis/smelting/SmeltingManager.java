package com.alcyone.jarvis.smelting;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Autonomous Smelting & Furnace Logistics Engine for Jarvis Companion.
 * Identifies raw ores, locates nearby vanilla and modded furnaces, deposits fuel
 * and raw materials into input slots, and collects finished ingots into companion backpack.
 */
public class SmeltingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    public record FurnaceCandidate(BlockPos pos, AbstractFurnaceBlockEntity furnace, boolean isBlastFurnace, double distSq) {}

    /**
     * Identifies whether an item is a smeltable raw ore.
     */
    public static boolean isRawOre(ItemStack stack, String filter) {
        if (stack == null || stack.isEmpty()) return false;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();

        boolean isRaw = path.startsWith("raw_") || path.endsWith("_ore") || path.contains("ancient_debris");
        if (!isRaw) return false;

        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter) || "ores".equalsIgnoreCase(filter)) {
            return true;
        }

        String f = filter.toLowerCase();
        return path.contains(f);
    }

    /**
     * Identifies whether an item is a valid furnace fuel.
     */
    public static boolean isFuel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL) || stack.is(Items.COAL_BLOCK)) return true;
        if (stack.is(Items.LAVA_BUCKET) || stack.is(Items.BLAZE_ROD)) return true;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        return path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_wood");
    }

    /**
     * Finds and ranks all nearby furnace candidates.
     */
    public static List<FurnaceCandidate> findFurnaces(ServerLevel level, BlockPos center, int radius) {
        List<FurnaceCandidate> candidates = new ArrayList<>();
        if (level == null || center == null) return candidates;

        int rad = Math.max(4, Math.min(radius, 48));
        BlockPos min = center.offset(-rad, -rad, -rad);
        BlockPos max = center.offset(rad, rad, rad);

        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof AbstractFurnaceBlockEntity furnace) {
                boolean isBlast = (furnace instanceof BlastFurnaceBlockEntity)
                        || BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath().contains("blast");
                double dSq = center.distSqr(p);
                candidates.add(new FurnaceCandidate(p.immutable(), furnace, isBlast, dSq));
            }
        }

        // Rank: Blast Furnaces first (2x speed for ores), then closer furnaces
        candidates.sort(Comparator.comparing((FurnaceCandidate c) -> !c.isBlastFurnace())
                .thenComparingDouble(FurnaceCandidate::distSq));

        return candidates;
    }

    /**
     * Autonomous furnace operation:
     * 1. Collects finished ingots from output slot (slot 2) into companion inventory.
     * 2. Inserts fuel into fuel slot (slot 1) if needed.
     * 3. Inserts raw ores into input slot (slot 0).
     */
    public static JsonObject operateFurnaces(ServerLevel level, JarvisCompanionEntity companion,
                                            BlockPos searchCenter, int radius, String filter) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        List<FurnaceCandidate> furnaces = findFurnaces(level, center, radius);
        if (furnaces.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "No furnaces found within radius " + radius);
            return result;
        }

        ItemStackHandler inv = companion.getInventory();
        int totalRawLoaded = 0;
        int totalFuelLoaded = 0;
        int totalIngotsCollected = 0;
        JsonArray loadedOresArr = new JsonArray();
        JsonArray collectedIngotsArr = new JsonArray();

        // 1. First Pass: Collect any finished ingots from all nearby furnaces
        for (FurnaceCandidate fc : furnaces) {
            AbstractFurnaceBlockEntity f = fc.furnace();
            ItemStack output = f.getItem(2);
            if (!output.isEmpty()) {
                int count = output.getCount();
                ItemStack remaining = companion.insertIntoInventory(output);
                int taken = count - remaining.getCount();
                if (taken > 0) {
                    totalIngotsCollected += taken;
                    JsonObject ingObj = new JsonObject();
                    ingObj.addProperty("item", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
                    ingObj.addProperty("count", taken);
                    ingObj.addProperty("name", output.getHoverName().getString());
                    collectedIngotsArr.add(ingObj);
                    f.setItem(2, remaining);
                    f.setChanged();
                }
            }
        }

        // 2. Second Pass: Distribute fuel and raw ores into available furnaces
        for (FurnaceCandidate fc : furnaces) {
            AbstractFurnaceBlockEntity f = fc.furnace();

            // A. Ensure Fuel in Slot 1
            ItemStack currentFuel = f.getItem(1);
            if (currentFuel.isEmpty() || currentFuel.getCount() < 16) {
                for (int slot = 0; slot < inv.getSlots(); slot++) {
                    ItemStack invStack = inv.getStackInSlot(slot);
                    if (isFuel(invStack)) {
                        int needed = 32 - currentFuel.getCount();
                        int toTake = Math.min(needed, invStack.getCount());
                        if (toTake > 0) {
                            if (currentFuel.isEmpty()) {
                                ItemStack fuelStack = inv.extractItem(slot, toTake, false);
                                f.setItem(1, fuelStack);
                                totalFuelLoaded += fuelStack.getCount();
                            } else if (ItemStack.isSameItemSameComponents(currentFuel, invStack)) {
                                inv.extractItem(slot, toTake, false);
                                currentFuel.grow(toTake);
                                totalFuelLoaded += toTake;
                            }
                            f.setChanged();
                            break;
                        }
                    }
                }
            }

            // B. Load Raw Ores into Slot 0
            ItemStack currentInput = f.getItem(0);
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack invStack = inv.getStackInSlot(slot);
                if (isRawOre(invStack, filter)) {
                    int maxCap = 64;
                    int currentCount = currentInput.isEmpty() ? 0 : currentInput.getCount();
                    int space = maxCap - currentCount;

                    if (space > 0 && (currentInput.isEmpty() || ItemStack.isSameItemSameComponents(currentInput, invStack))) {
                        int toTake = Math.min(space, invStack.getCount());
                        if (toTake > 0) {
                            if (currentInput.isEmpty()) {
                                ItemStack inputStack = inv.extractItem(slot, toTake, false);
                                f.setItem(0, inputStack);
                                currentInput = inputStack;
                                totalRawLoaded += inputStack.getCount();
                                JsonObject oreObj = new JsonObject();
                                oreObj.addProperty("item", BuiltInRegistries.ITEM.getKey(inputStack.getItem()).toString());
                                oreObj.addProperty("count", inputStack.getCount());
                                oreObj.addProperty("name", inputStack.getHoverName().getString());
                                loadedOresArr.add(oreObj);
                            } else {
                                inv.extractItem(slot, toTake, false);
                                currentInput.grow(toTake);
                                totalRawLoaded += toTake;
                                JsonObject oreObj = new JsonObject();
                                oreObj.addProperty("item", BuiltInRegistries.ITEM.getKey(currentInput.getItem()).toString());
                                oreObj.addProperty("count", toTake);
                                oreObj.addProperty("name", currentInput.getHoverName().getString());
                                loadedOresArr.add(oreObj);
                            }
                            f.setChanged();
                        }
                    }
                }
            }
        }

        if (totalRawLoaded > 0 || totalIngotsCollected > 0) {
            BlockPos primaryFurnace = furnaces.get(0).pos();
            level.playSound(null, primaryFurnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        result.addProperty("success", true);
        result.addProperty("furnaces_found", furnaces.size());
        result.addProperty("raw_loaded_total", totalRawLoaded);
        result.addProperty("fuel_loaded_total", totalFuelLoaded);
        result.addProperty("ingots_collected_total", totalIngotsCollected);
        result.add("loaded_ores", loadedOresArr);
        result.add("collected_ingots", collectedIngotsArr);

        return result;
    }

    /**
     * Collects all finished ingots from nearby furnaces.
     */
    public static JsonObject collectFurnaceOutputs(ServerLevel level, JarvisCompanionEntity companion,
                                                   BlockPos searchCenter, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        List<FurnaceCandidate> furnaces = findFurnaces(level, center, radius);
        if (furnaces.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "No furnaces found within radius " + radius);
            return result;
        }

        int totalCollected = 0;
        JsonArray collectedArr = new JsonArray();

        for (FurnaceCandidate fc : furnaces) {
            AbstractFurnaceBlockEntity f = fc.furnace();
            ItemStack output = f.getItem(2);
            if (!output.isEmpty()) {
                int count = output.getCount();
                ItemStack remaining = companion.insertIntoInventory(output);
                int taken = count - remaining.getCount();
                if (taken > 0) {
                    totalCollected += taken;
                    JsonObject ingObj = new JsonObject();
                    ingObj.addProperty("item", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
                    ingObj.addProperty("count", taken);
                    ingObj.addProperty("name", output.getHoverName().getString());
                    collectedArr.add(ingObj);
                    f.setItem(2, remaining);
                    f.setChanged();
                }
            }
        }

        if (totalCollected > 0) {
            level.playSound(null, companion.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.0F);
        }

        result.addProperty("success", true);
        result.addProperty("furnaces_checked", furnaces.size());
        result.addProperty("ingots_collected_total", totalCollected);
        result.add("collected_ingots", collectedArr);

        return result;
    }
}
