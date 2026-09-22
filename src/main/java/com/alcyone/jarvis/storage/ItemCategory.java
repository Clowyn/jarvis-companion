package com.alcyone.jarvis.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;

import java.util.Arrays;
import java.util.List;

/**
 * Direwolf20-aware categorization hierarchy for items and containers.
 * Strict priority from 1 (Ores & Minerals) down to 7 (Miscellaneous).
 */
public enum ItemCategory {
    ORES_MINERALS(1, "Ores", "ores", "minerals", "metals", "raw", "maden", "madenler", "cevher"),
    BUILDING_BLOCKS(2, "Building", "building", "blocks", "construction", "blok", "bloklar", "yapi", "yapı"),
    TOOLS_WEAPONS_ARMOR(3, "Equipment", "tools", "weapons", "armor", "equipment", "ekipman", "ekipmanlar", "alet", "silah", "zirh"),
    FOOD(4, "Food", "food", "yemek", "yemekler", "yiyecek", "yiyecekler"),
    FARMING(5, "Farming", "farming", "tarim", "tarım", "crops", "seeds", "tohum", "ekin"),
    MATERIALS_MISC(6, "Materials", "materials", "malzeme", "malzemeler", "loot", "mob", "crafting", "ivirzivir", "ıvırzıvır"),
    TECH_MACHINERY(7, "Tech", "tech", "machinery", "automation", "makine", "teknoloji"),
    MAGIC_ALCHEMY(8, "Magic", "magic", "alchemy", "buyu", "büyü", "simya"),
    MISCELLANEOUS(9, "Misc", "misc", "other", "diger", "diğer");

    private final int priority;
    private final String displayName;
    private final List<String> aliases;

    ItemCategory(int priority, String displayName, String... aliases) {
        this.priority = priority;
        this.displayName = displayName;
        this.aliases = Arrays.asList(aliases);
    }

    public int getPriority() {
        return priority;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean matchesFilter(String filter) {
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            return true;
        }
        String f = filter.trim().toLowerCase();
        if (this.name().equalsIgnoreCase(f) || this.displayName.equalsIgnoreCase(f)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classifies an ItemStack into one of the Direwolf20 categories.
     */
    public static ItemCategory classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MISCELLANEOUS;
        }

        Item item = stack.getItem();
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        String namespace = loc.getNamespace().toLowerCase();
        String path = loc.getPath().toLowerCase();

        // 1. Ores & Minerals
        if (isOreOrMineral(stack, item, namespace, path)) {
            return ORES_MINERALS;
        }

        // 2. Food
        if (isFood(stack, item, namespace, path)) {
            return FOOD;
        }

        // 3. Farming & Agriculture
        if (isFarming(stack, item, namespace, path)) {
            return FARMING;
        }

        // 4. Building Blocks
        if (isBuildingBlock(stack, item, namespace, path)) {
            return BUILDING_BLOCKS;
        }

        // 5. Tools, Weapons & Armor
        if (isToolWeaponOrArmor(stack, item, namespace, path)) {
            return TOOLS_WEAPONS_ARMOR;
        }

        // 6. Materials & Mob Loot
        if (isMaterialOrMisc(stack, item, namespace, path)) {
            return MATERIALS_MISC;
        }

        // 7. Tech & Machinery
        if (isTechOrMachinery(stack, item, namespace, path)) {
            return TECH_MACHINERY;
        }

        // 8. Magic & Alchemy
        if (isMagicOrAlchemy(stack, item, namespace, path)) {
            return MAGIC_ALCHEMY;
        }

        // 9. Miscellaneous
        return MISCELLANEOUS;
    }

    private static boolean isOreOrMineral(ItemStack stack, Item item, String namespace, String path) {
        // Tag checks
        if (stack.is(Tags.Items.ORES)
                || stack.is(Tags.Items.RAW_MATERIALS)
                || stack.is(Tags.Items.INGOTS)
                || stack.is(Tags.Items.GEMS)
                || stack.is(Tags.Items.NUGGETS)
                || stack.is(Tags.Items.DUSTS)) {
            return true;
        }
        if (hasTag(stack, "c:ores")
                || hasTag(stack, "c:raw_materials")
                || hasTag(stack, "c:ingots")
                || hasTag(stack, "c:gems")
                || hasTag(stack, "c:nuggets")
                || hasTag(stack, "c:dusts")) {
            return true;
        }

        if (stack.is(Tags.Items.STORAGE_BLOCKS)
                || hasTag(stack, "c:storage_blocks")
                || hasTag(stack, "c:storage_blocks/metals")
                || hasTag(stack, "c:storage_blocks/raw_materials")) {
            if (!path.contains("hay") && !path.contains("wheat") && !path.contains("kelp") && !path.contains("bone")) {
                return true;
            }
        }

        // Specific vanilla items
        if (item == Items.COAL || item == Items.CHARCOAL || item == Items.REDSTONE
                || item == Items.LAPIS_LAZULI || item == Items.QUARTZ || item == Items.DIAMOND
                || item == Items.EMERALD || item == Items.NETHERITE_INGOT || item == Items.NETHERITE_SCRAP
                || item == Items.ANCIENT_DEBRIS || item == Items.RAW_IRON || item == Items.RAW_COPPER
                || item == Items.RAW_GOLD || item == Items.IRON_INGOT || item == Items.GOLD_INGOT
                || item == Items.COPPER_INGOT || item == Items.AMETHYST_SHARD || item == Items.FLINT
                || item == Items.PRISMARINE_CRYSTALS || item == Items.PRISMARINE_SHARD
                || item == Items.IRON_BLOCK || item == Items.GOLD_BLOCK || item == Items.DIAMOND_BLOCK
                || item == Items.NETHERITE_BLOCK || item == Items.COPPER_BLOCK || item == Items.REDSTONE_BLOCK
                || item == Items.LAPIS_BLOCK || item == Items.COAL_BLOCK || item == Items.RAW_IRON_BLOCK
                || item == Items.RAW_COPPER_BLOCK || item == Items.RAW_GOLD_BLOCK || item == Items.AMETHYST_BLOCK) {
            return true;
        }

        // Name heuristics for modded ores/minerals and their block forms
        if (path.endsWith("_block") || path.startsWith("block_")) {
            if (path.contains("iron") || path.contains("gold") || path.contains("copper") || path.contains("diamond")
                    || path.contains("emerald") || path.contains("netherite") || path.contains("redstone")
                    || path.contains("lapis") || path.contains("coal") || path.contains("quartz")
                    || path.contains("amethyst") || path.contains("osmium") || path.contains("tin")
                    || path.contains("lead") || path.contains("uranium") || path.contains("zinc")
                    || path.contains("steel") || path.contains("bronze") || path.contains("brass")
                    || path.contains("nickel") || path.contains("silver") || path.contains("platinum")
                    || path.contains("electrum") || path.contains("constantan") || path.contains("invar")
                    || path.contains("aluminum") || path.contains("alloys") || path.contains("alloy")
                    || path.contains("fluorite") || path.contains("sulfur") || path.contains("salt")
                    || path.contains("uraninite") || path.contains("sal_ammoniac") || path.contains("certus")
                    || path.contains("fluix") || path.contains("raw_") || path.contains("blazegold")
                    || path.contains("ferricore") || path.contains("celestigem") || path.contains("eclipsealloy")) {
                return true;
            }
        }

        return path.endsWith("_ore") || path.startsWith("raw_") || path.endsWith("_raw")
                || path.endsWith("_ingot") || path.endsWith("_gem") || path.endsWith("_nugget")
                || path.endsWith("_dust") || path.contains("clump") || path.contains("crystal")
                || path.contains("cluster") || path.contains("debris");
    }

    private static boolean isBuildingBlock(ItemStack stack, Item item, String namespace, String path) {
        if (item == Items.DIRT || item == Items.COARSE_DIRT || item == Items.ROOTED_DIRT
                || item == Items.MUD || item == Items.CLAY || item == Items.GRAVEL
                || item == Items.SAND || item == Items.RED_SAND || item == Items.BRICKS
                || item == Items.NETHER_BRICKS || item == Items.RED_NETHER_BRICKS) {
            return true;
        }

        if (stack.is(Tags.Items.STONES)
                || stack.is(Tags.Items.COBBLESTONES)
                || stack.is(Tags.Items.SANDS)
                || stack.is(Tags.Items.GRAVELS)
                || stack.is(Tags.Items.GLASS_BLOCKS)
                || stack.is(Tags.Items.GLASS_PANES)) {
            return true;
        }

        if (stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.WOODEN_FENCES)
                || stack.is(ItemTags.WOOL)
                || stack.is(ItemTags.TERRACOTTA)
                || stack.is(ItemTags.SLABS)
                || stack.is(ItemTags.STAIRS)
                || stack.is(ItemTags.WALLS)
                || stack.is(ItemTags.STONE_BRICKS)) {
            return true;
        }

        if (hasTag(stack, "c:stones")
                || hasTag(stack, "c:cobblestones")
                || hasTag(stack, "c:sand")
                || hasTag(stack, "c:gravel")
                || hasTag(stack, "c:glass_blocks")
                || hasTag(stack, "c:glass_panes")
                || hasTag(stack, "c:concretes")) {
            return true;
        }

        // Check if item is a block item representing construction/building blocks
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            String bpath = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase();
            return bpath.contains("stone") || bpath.contains("cobble") || bpath.contains("dirt")
                    || bpath.contains("sand") || bpath.contains("gravel") || bpath.contains("wood")
                    || bpath.contains("log") || bpath.contains("plank") || bpath.contains("brick")
                    || bpath.contains("glass") || bpath.contains("concrete") || bpath.contains("terracotta")
                    || bpath.contains("deepslate") || bpath.contains("tuff") || bpath.contains("granite")
                    || bpath.contains("diorite") || bpath.contains("andesite") || bpath.contains("basalt")
                    || bpath.contains("blackstone") || bpath.contains("netherrack") || bpath.contains("end_stone")
                    || bpath.contains("slab") || bpath.contains("stair") || bpath.contains("wall");
        }

        return false;
    }

    private static boolean isToolWeaponOrArmor(ItemStack stack, Item item, String namespace, String path) {
        if (item instanceof TieredItem
                || item instanceof ArmorItem
                || item instanceof ShieldItem
                || item instanceof ProjectileWeaponItem
                || item instanceof TridentItem
                || item instanceof MaceItem
                || item instanceof ShearsItem
                || item instanceof FishingRodItem
                || item instanceof Equipable) {
            return true;
        }

        if (stack.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            return true;
        }

        // Tags
        if (stack.is(Tags.Items.TOOLS) || stack.is(Tags.Items.ARMORS)) {
            return true;
        }
        if (stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES)
                || stack.is(ItemTags.HEAD_ARMOR)
                || stack.is(ItemTags.CHEST_ARMOR)
                || stack.is(ItemTags.LEG_ARMOR)
                || stack.is(ItemTags.FOOT_ARMOR)
                || hasTag(stack, "c:bows")) {
            return true;
        }

        if (hasTag(stack, "c:tools") || hasTag(stack, "c:armors") || hasTag(stack, "c:shields")) {
            return true;
        }

        if (item == Items.TOTEM_OF_UNDYING || item == Items.ELYTRA || item == Items.COMPASS
                || item == Items.CLOCK || item == Items.SPYGLASS || item == Items.LEAD
                || item == Items.FLINT_AND_STEEL) {
            return true;
        }

        return path.contains("sword") || path.contains("pickaxe") || path.contains("axe")
                || path.contains("shovel") || path.contains("hoe") || path.contains("helmet")
                || path.contains("chestplate") || path.contains("leggings") || path.contains("boots")
                || path.contains("shield") || path.contains("bow") || path.contains("totem");
    }

    private static boolean isFood(ItemStack stack, Item item, String namespace, String path) {
        if (stack.has(DataComponents.FOOD)) {
            // Exclude poisonous/mob items like rotten flesh or spider eyes from dedicated food chest
            if (item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE || item == Items.POISONOUS_POTATO) {
                return false;
            }
            return true;
        }

        if (stack.is(Tags.Items.FOODS) || hasTag(stack, "c:foods")) {
            if (item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE) return false;
            return true;
        }

        return item == Items.APPLE || item == Items.BREAD || item == Items.BEEF || item == Items.COOKED_BEEF
                || item == Items.PORKCHOP || item == Items.COOKED_PORKCHOP || item == Items.CHICKEN
                || item == Items.COOKED_CHICKEN || item == Items.MUTTON || item == Items.COOKED_MUTTON
                || item == Items.RABBIT || item == Items.COOKED_RABBIT || item == Items.COD
                || item == Items.COOKED_COD || item == Items.SALMON || item == Items.COOKED_SALMON
                || item == Items.CARROT || item == Items.GOLDEN_CARROT || item == Items.POTATO
                || item == Items.BAKED_POTATO || item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE
                || item == Items.MELON_SLICE || item == Items.SWEET_BERRIES || item == Items.GLOW_BERRIES
                || item == Items.HONEY_BOTTLE || item == Items.COOKIE || item == Items.PUMPKIN_PIE
                || item == Items.MUSHROOM_STEW || item == Items.BEETROOT_SOUP || item == Items.RABBIT_STEW
                || item == Items.SUSPICIOUS_STEW || item == Items.DRIED_KELP || item == Items.CHORUS_FRUIT
                || path.contains("meat") || path.contains("steak") || path.contains("stew")
                || path.contains("soup") || path.contains("pie") || path.contains("cookie")
                || path.contains("bread");
    }

    private static boolean isFarming(ItemStack stack, Item item, String namespace, String path) {
        if (stack.is(Tags.Items.CROPS) || stack.is(Tags.Items.SEEDS)
                || hasTag(stack, "c:crops") || hasTag(stack, "c:seeds")) {
            return true;
        }

        if (stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(ItemTags.LEAVES)
                || stack.is(ItemTags.FLOWERS)) {
            return true;
        }

        return item == Items.WHEAT || item == Items.WHEAT_SEEDS || item == Items.PUMPKIN_SEEDS
                || item == Items.MELON_SEEDS || item == Items.BEETROOT || item == Items.BEETROOT_SEEDS
                || item == Items.TORCHFLOWER_SEEDS || item == Items.PITCHER_POD || item == Items.SUGAR_CANE
                || item == Items.CACTUS || item == Items.BAMBOO || item == Items.NETHER_WART
                || item == Items.COCOA_BEANS || item == Items.BONE_MEAL || item == Items.KELP
                || item == Items.PUMPKIN || item == Items.MELON || item == Items.HAY_BLOCK
                || item == Items.DRIED_KELP_BLOCK || item == Items.CARVED_PUMPKIN || item == Items.JACK_O_LANTERN
                || path.contains("seed") || path.contains("crop") || path.contains("sapling")
                || path.contains("flower") || path.contains("plant") || path.contains("hay");
    }

    private static boolean isMaterialOrMisc(ItemStack stack, Item item, String namespace, String path) {
        // Mob drops and crafting materials (including their block forms)
        if (item == Items.BONE || item == Items.GUNPOWDER || item == Items.STRING
                || item == Items.SPIDER_EYE || item == Items.ROTTEN_FLESH || item == Items.ENDER_PEARL
                || item == Items.BLAZE_ROD || item == Items.GHAST_TEAR || item == Items.MAGMA_CREAM
                || item == Items.LEATHER || item == Items.FEATHER || item == Items.SLIME_BALL
                || item == Items.PHANTOM_MEMBRANE || item == Items.HONEYCOMB || item == Items.EGG
                || item == Items.INK_SAC || item == Items.GLOW_INK_SAC || item == Items.RABBIT_FOOT
                || item == Items.RABBIT_HIDE || item == Items.PRISMARINE_SHARD || item == Items.PRISMARINE_CRYSTALS
                || item == Items.NAUTILUS_SHELL || item == Items.TURTLE_SCUTE || item == Items.ECHO_SHARD
                || item == Items.BREEZE_ROD || item == Items.POISONOUS_POTATO || item == Items.SHULKER_SHELL
                || item == Items.BONE_BLOCK || item == Items.SLIME_BLOCK || item == Items.HONEYCOMB_BLOCK) {
            return true;
        }

        // Basic crafting components
        if (item == Items.STICK || item == Items.PAPER || item == Items.BOOK
                || item == Items.BOWL || item == Items.FLINT || item == Items.CLAY_BALL
                || item == Items.BRICK || item == Items.NETHER_BRICK || item == Items.BLAZE_POWDER
                || item == Items.FERMENTED_SPIDER_EYE || item == Items.FIRE_CHARGE
                || item == Items.ARMADILLO_SCUTE) {
            return true;
        }

        return path.contains("leather") || path.contains("feather") || path.contains("string")
                || path.contains("bone") || path.contains("pearl") || path.contains("powder")
                || path.contains("drop") || path.contains("material");
    }

    private static boolean isTechOrMachinery(ItemStack stack, Item item, String namespace, String path) {
        // Mod namespaces from Direwolf20 tech ecosystem
        if (namespace.equals("mekanism") || namespace.equals("mekanismgenerators")
                || namespace.equals("ae2") || namespace.equals("appflux") || namespace.equals("extendedae")
                || namespace.equals("powah") || namespace.equals("create") || namespace.equals("enderio")
                || namespace.equals("justdirethings") || namespace.equals("oritech")
                || namespace.equals("industrialforegoing") || namespace.equals("thermal")
                || namespace.equals("refinedstorage") || namespace.equals("pneumaticcraft")
                || namespace.equals("integrateddynamics") || namespace.equals("fluxnetworks")
                || namespace.equals("draconicevolution")) {
            return true;
        }

        // Tags
        if (hasTag(stack, "c:gears") || hasTag(stack, "c:wires") || hasTag(stack, "c:circuits")) {
            return true;
        }

        return path.contains("cable") || path.contains("pipe") || path.contains("conduit")
                || path.contains("processor") || path.contains("storage_cell") || path.contains("machine_frame")
                || path.contains("motor") || path.contains("cog") || path.contains("circuit")
                || path.contains("wire") || path.contains("wrench") || path.contains("battery")
                || path.contains("energy_cell") || path.contains("generator") || path.contains("reactor")
                || path.contains("infuser") || path.contains("crusher");
    }

    private static boolean isMagicOrAlchemy(ItemStack stack, Item item, String namespace, String path) {
        // Mod namespaces from Direwolf20 magic ecosystem
        if (namespace.equals("ars_nouveau") || namespace.equals("occultism")
                || namespace.equals("botania") || namespace.equals("bloodmagic")
                || namespace.equals("evilcraft") || namespace.equals("mahoutsukai")
                || namespace.equals("irons_spellbooks") || namespace.equals("apotheosis")) {
            return true;
        }

        // Potions and alchemy
        if (stack.has(DataComponents.POTION_CONTENTS)
                || item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
            return true;
        }

        // Magic items
        if (item == Items.ENCHANTED_BOOK || item == Items.EXPERIENCE_BOTTLE
                || item == Items.BREWING_STAND || item == Items.ENCHANTING_TABLE
                || item == Items.BLAZE_POWDER || item == Items.FERMENTED_SPIDER_EYE) {
            return true;
        }

        return path.contains("spell") || path.contains("rune") || path.contains("scroll")
                || path.contains("wand") || path.contains("tome") || path.contains("potion")
                || path.contains("elixir") || path.contains("essence") || path.contains("glyph");
    }

    private static boolean hasTag(ItemStack stack, String tagId) {
        try {
            ResourceLocation loc = ResourceLocation.tryParse(tagId);
            if (loc != null) {
                TagKey<Item> tagKey = TagKey.create(Registries.ITEM, loc);
                return stack.is(tagKey);
            }
        } catch (Exception ignored) {}
        return false;
    }
}
