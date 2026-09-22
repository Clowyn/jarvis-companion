package com.alcyone.jarvis.crafting;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Autonomous Crafting Engine for Jarvis Companion.
 * Allows Jarvis to autonomously fabricate essential tools, weapons, armor,
 * torches, food, and storage items directly from inventory materials.
 */
public class CraftingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    /**
     * Crafts a specified item in the requested quantity from available materials.
     */
    public static JsonObject craftItem(ServerLevel level, JarvisCompanionEntity companion,
                                      String targetItemId, int count) {
        JsonObject result = new JsonObject();
        if (companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        String target = targetItemId.toLowerCase().trim();
        if (!target.contains(":")) {
            target = "minecraft:" + target;
        }

        ItemStackHandler inv = companion.getInventory();
        int requested = Math.max(1, count);
        int crafted = 0;

        switch (target) {
            case "minecraft:oak_planks":
            case "minecraft:spruce_planks":
            case "minecraft:birch_planks":
            case "minecraft:jungle_planks":
            case "minecraft:acacia_planks":
            case "minecraft:dark_oak_planks":
            case "minecraft:cherry_planks":
            case "minecraft:planks": {
                // 1 log -> 4 planks
                while (crafted < requested) {
                    if (!consumeAnyLog(inv)) break;
                    addItemsToInventory(inv, new ItemStack(Items.OAK_PLANKS, 4));
                    crafted += 4;
                }
                break;
            }

            case "minecraft:stick":
            case "minecraft:sticks": {
                // 2 planks -> 4 sticks
                ensurePlanks(inv, (requested - crafted + 3) / 4 * 2);
                while (crafted < requested) {
                    if (!consumeItem(inv, ItemTags.PLANKS, 2)) break;
                    addItemsToInventory(inv, new ItemStack(Items.STICK, 4));
                    crafted += 4;
                }
                break;
            }

            case "minecraft:torch":
            case "minecraft:torches": {
                // 1 coal/charcoal + 1 stick -> 4 torches
                ensureSticks(inv, (requested - crafted + 3) / 4);
                while (crafted < requested) {
                    if (!hasCoal(inv) || !consumeStick(inv, 1)) break;
                    consumeCoal(inv, 1);
                    addItemsToInventory(inv, new ItemStack(Items.TORCH, 4));
                    crafted += 4;
                }
                break;
            }

            case "minecraft:crafting_table": {
                // 4 planks -> 1 crafting table
                while (crafted < requested) {
                    ensurePlanks(inv, 4);
                    if (!consumeItem(inv, ItemTags.PLANKS, 4)) break;
                    addItemsToInventory(inv, new ItemStack(Items.CRAFTING_TABLE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:chest": {
                // 8 planks -> 1 chest
                while (crafted < requested) {
                    ensurePlanks(inv, 8);
                    if (!consumeItem(inv, ItemTags.PLANKS, 8)) break;
                    addItemsToInventory(inv, new ItemStack(Items.CHEST, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:bread": {
                // 3 wheat -> 1 bread
                while (crafted < requested) {
                    if (!consumeItemExact(inv, Items.WHEAT, 3)) break;
                    addItemsToInventory(inv, new ItemStack(Items.BREAD, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:shears": {
                // 2 iron ingots -> 1 shears
                while (crafted < requested) {
                    if (!consumeItemExact(inv, Items.IRON_INGOT, 2)) break;
                    addItemsToInventory(inv, new ItemStack(Items.SHEARS, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:bucket": {
                // 3 iron ingots -> 1 bucket
                while (crafted < requested) {
                    if (!consumeItemExact(inv, Items.IRON_INGOT, 3)) break;
                    addItemsToInventory(inv, new ItemStack(Items.BUCKET, 1));
                    crafted += 1;
                }
                break;
            }

            // --- Tools ---
            case "minecraft:diamond_pickaxe": {
                while (crafted < requested) {
                    ensureSticks(inv, 2);
                    if (!hasExact(inv, Items.DIAMOND, 3) || !consumeStick(inv, 2)) break;
                    consumeItemExact(inv, Items.DIAMOND, 3);
                    addItemsToInventory(inv, new ItemStack(Items.DIAMOND_PICKAXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:iron_pickaxe": {
                while (crafted < requested) {
                    ensureSticks(inv, 2);
                    if (!hasExact(inv, Items.IRON_INGOT, 3) || !consumeStick(inv, 2)) break;
                    consumeItemExact(inv, Items.IRON_INGOT, 3);
                    addItemsToInventory(inv, new ItemStack(Items.IRON_PICKAXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:stone_pickaxe": {
                while (crafted < requested) {
                    ensureSticks(inv, 2);
                    if (!hasCobblestone(inv, 3) || !consumeStick(inv, 2)) break;
                    consumeCobblestone(inv, 3);
                    addItemsToInventory(inv, new ItemStack(Items.STONE_PICKAXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:wooden_pickaxe": {
                while (crafted < requested) {
                    ensurePlanks(inv, 3);
                    ensureSticks(inv, 2);
                    if (!consumeItem(inv, ItemTags.PLANKS, 3) || !consumeStick(inv, 2)) break;
                    addItemsToInventory(inv, new ItemStack(Items.WOODEN_PICKAXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:diamond_axe": {
                while (crafted < requested) {
                    ensureSticks(inv, 2);
                    if (!hasExact(inv, Items.DIAMOND, 3) || !consumeStick(inv, 2)) break;
                    consumeItemExact(inv, Items.DIAMOND, 3);
                    addItemsToInventory(inv, new ItemStack(Items.DIAMOND_AXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:iron_axe": {
                while (crafted < requested) {
                    ensureSticks(inv, 2);
                    if (!hasExact(inv, Items.IRON_INGOT, 3) || !consumeStick(inv, 2)) break;
                    consumeItemExact(inv, Items.IRON_INGOT, 3);
                    addItemsToInventory(inv, new ItemStack(Items.IRON_AXE, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:diamond_sword": {
                while (crafted < requested) {
                    ensureSticks(inv, 1);
                    if (!hasExact(inv, Items.DIAMOND, 2) || !consumeStick(inv, 1)) break;
                    consumeItemExact(inv, Items.DIAMOND, 2);
                    addItemsToInventory(inv, new ItemStack(Items.DIAMOND_SWORD, 1));
                    crafted += 1;
                }
                break;
            }

            case "minecraft:iron_sword": {
                while (crafted < requested) {
                    ensureSticks(inv, 1);
                    if (!hasExact(inv, Items.IRON_INGOT, 2) || !consumeStick(inv, 1)) break;
                    consumeItemExact(inv, Items.IRON_INGOT, 2);
                    addItemsToInventory(inv, new ItemStack(Items.IRON_SWORD, 1));
                    crafted += 1;
                }
                break;
            }

            default:
                result.addProperty("success", false);
                result.addProperty("error", "Recipe not supported or unknown item: " + target);
                return result;
        }

        if (crafted > 0) {
            level.playSound(null, companion.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            result.addProperty("success", true);
            result.addProperty("item", target);
            result.addProperty("crafted_count", crafted);
        } else {
            result.addProperty("success", false);
            result.addProperty("error", "Insufficient ingredients in companion inventory to craft " + target);
        }

        return result;
    }

    /**
     * Checks companion tools and auto-crafts missing tools (Pickaxe, Axe, Torches)
     * using materials available in inventory.
     */
    public static JsonObject autoReplenishTools(ServerLevel level, JarvisCompanionEntity companion) {
        JsonObject result = new JsonObject();
        if (companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        ItemStackHandler inv = companion.getInventory();
        JsonArray craftedItems = new JsonArray();

        // 1. Check Pickaxe
        if (!hasToolType(inv, "pickaxe")) {
            if (hasExact(inv, Items.DIAMOND, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:diamond_pickaxe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("diamond_pickaxe");
            } else if (hasExact(inv, Items.IRON_INGOT, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:iron_pickaxe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("iron_pickaxe");
            } else if (hasCobblestone(inv, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:stone_pickaxe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("stone_pickaxe");
            } else if (hasPlanksOrLogs(inv, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:wooden_pickaxe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("wooden_pickaxe");
            }
        }

        // 2. Check Axe
        if (!hasToolType(inv, "axe")) {
            if (hasExact(inv, Items.DIAMOND, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:diamond_axe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("diamond_axe");
            } else if (hasExact(inv, Items.IRON_INGOT, 3)) {
                JsonObject r = craftItem(level, companion, "minecraft:iron_axe", 1);
                if (r.get("success").getAsBoolean()) craftedItems.add("iron_axe");
            }
        }

        // 3. Check Torches (< 8 torches -> craft up to 16)
        int torchCount = countItemExact(inv, Items.TORCH);
        if (torchCount < 8 && hasCoal(inv)) {
            JsonObject r = craftItem(level, companion, "minecraft:torch", 16);
            if (r.get("success").getAsBoolean()) craftedItems.add("torches");
        }

        // 4. Check Food (Wheat -> Bread)
        int wheatCount = countItemExact(inv, Items.WHEAT);
        if (wheatCount >= 3) {
            JsonObject r = craftItem(level, companion, "minecraft:bread", wheatCount / 3);
            if (r.get("success").getAsBoolean()) craftedItems.add("bread");
        }

        result.addProperty("success", true);
        result.addProperty("replenished_count", craftedItems.size());
        result.add("crafted_items", craftedItems);

        return result;
    }

    // --- Helper Methods ---

    private static boolean hasToolType(ItemStackHandler inv, String type) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                if (id.contains(type)) return true;
            }
        }
        return false;
    }

    private static boolean hasPlanksOrLogs(ItemStackHandler inv, int amount) {
        int planks = countItemTag(inv, ItemTags.PLANKS);
        int logs = countItemTag(inv, ItemTags.LOGS);
        return (planks + logs * 4) >= amount;
    }

    private static void ensurePlanks(ItemStackHandler inv, int needed) {
        int current = countItemTag(inv, ItemTags.PLANKS);
        if (current < needed) {
            int toCraft = needed - current;
            int logsNeeded = (toCraft + 3) / 4;
            for (int i = 0; i < logsNeeded; i++) {
                if (consumeAnyLog(inv)) {
                    addItemsToInventory(inv, new ItemStack(Items.OAK_PLANKS, 4));
                }
            }
        }
    }

    private static void ensureSticks(ItemStackHandler inv, int needed) {
        int current = countItemExact(inv, Items.STICK);
        if (current < needed) {
            int toCraft = needed - current;
            int batches = (toCraft + 3) / 4;
            ensurePlanks(inv, batches * 2);
            for (int i = 0; i < batches; i++) {
                if (consumeItem(inv, ItemTags.PLANKS, 2)) {
                    addItemsToInventory(inv, new ItemStack(Items.STICK, 4));
                }
            }
        }
    }

    private static boolean consumeAnyLog(ItemStackHandler inv) {
        return consumeItem(inv, ItemTags.LOGS, 1);
    }

    private static boolean hasCoal(ItemStackHandler inv) {
        return hasExact(inv, Items.COAL, 1) || hasExact(inv, Items.CHARCOAL, 1);
    }

    private static boolean consumeCoal(ItemStackHandler inv, int amount) {
        if (consumeItemExact(inv, Items.COAL, amount)) return true;
        return consumeItemExact(inv, Items.CHARCOAL, amount);
    }

    private static boolean hasCobblestone(ItemStackHandler inv, int amount) {
        return countItemExact(inv, Items.COBBLESTONE) >= amount;
    }

    private static boolean consumeCobblestone(ItemStackHandler inv, int amount) {
        return consumeItemExact(inv, Items.COBBLESTONE, amount);
    }

    private static boolean consumeStick(ItemStackHandler inv, int amount) {
        return consumeItemExact(inv, Items.STICK, amount);
    }

    private static boolean hasExact(ItemStackHandler inv, Item item, int amount) {
        return countItemExact(inv, item) >= amount;
    }

    private static int countItemExact(ItemStackHandler inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(item)) count += s.getCount();
        }
        return count;
    }

    private static int countItemTag(ItemStackHandler inv, net.minecraft.tags.TagKey<Item> tag) {
        int count = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(tag)) count += s.getCount();
        }
        return count;
    }

    private static boolean consumeItemExact(ItemStackHandler inv, Item item, int amount) {
        if (countItemExact(inv, item) < amount) return false;
        int remaining = amount;
        for (int i = 0; i < inv.getSlots() && remaining > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(item)) {
                ItemStack extracted = inv.extractItem(i, remaining, false);
                remaining -= extracted.getCount();
            }
        }
        return remaining == 0;
    }

    private static boolean consumeItem(ItemStackHandler inv, net.minecraft.tags.TagKey<Item> tag, int amount) {
        if (countItemTag(inv, tag) < amount) return false;
        int remaining = amount;
        for (int i = 0; i < inv.getSlots() && remaining > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(tag)) {
                ItemStack extracted = inv.extractItem(i, remaining, false);
                remaining -= extracted.getCount();
            }
        }
        return remaining == 0;
    }

    private static void addItemsToInventory(ItemStackHandler inv, ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = 0; i < inv.getSlots() && !remainder.isEmpty(); i++) {
            remainder = inv.insertItem(i, remainder, false);
        }
    }
}
