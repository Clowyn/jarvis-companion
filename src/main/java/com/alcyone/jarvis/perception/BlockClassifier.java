package com.alcyone.jarvis.perception;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Classifies blocks into semantic categories for perception scans using NeoForge common tags,
 * vanilla tags, and block entity detection, while filtering natural environmental clutter.
 */
public class BlockClassifier {

    /**
     * Checks if a block is common natural filler that should be skipped during perception scans.
     */
    public static boolean isNaturalFiller(Block block, BlockState state) {
        if (state.isAir()) {
            return true;
        }

        if (state.is(BlockTags.LEAVES)) {
            return true;
        }

        return block == Blocks.STONE
                || block == Blocks.GRANITE
                || block == Blocks.DIORITE
                || block == Blocks.ANDESITE
                || block == Blocks.DEEPSLATE
                || block == Blocks.TUFF
                || block == Blocks.CALCITE
                || block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.MUD
                || block == Blocks.GRAVEL
                || block == Blocks.SAND
                || block == Blocks.RED_SAND
                || block == Blocks.SANDSTONE
                || block == Blocks.RED_SANDSTONE
                || block == Blocks.WATER
                || block == Blocks.BEDROCK
                || block == Blocks.NETHERRACK
                || block == Blocks.BASALT
                || block == Blocks.BLACKSTONE
                || block == Blocks.END_STONE
                || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS
                || block == Blocks.SEAGRASS
                || block == Blocks.TALL_SEAGRASS
                || block == Blocks.KELP
                || block == Blocks.KELP_PLANT
                || block == Blocks.VINE
                || block == Blocks.GLOW_LICHEN
                || block == Blocks.SNOW
                || block == Blocks.SNOW_BLOCK
                || block == Blocks.ICE;
    }

    /**
     * Classifies a block state into a BlockCategory.
     */
    public static BlockCategory classify(BlockState state) {
        if (state.isAir()) {
            return BlockCategory.NONE;
        }

        Block block = state.getBlock();

        // 1. Ores (NeoForge Tags.Blocks.ORES + Vanilla ore blocks)
        if (state.is(Tags.Blocks.ORES) || isOreBlock(block)) {
            return BlockCategory.ORE;
        }

        // 2. Containers (NeoForge Tags.Blocks.CHESTS, BARRELS, Shulker boxes, and vanilla containers)
        if (state.is(Tags.Blocks.CHESTS)
                || state.is(Tags.Blocks.BARRELS)
                || state.is(BlockTags.SHULKER_BOXES)
                || isContainerBlock(block)) {
            return BlockCategory.CONTAINER;
        }

        // 3. Workstations (Crafting tables, furnaces, anvils, enchanting tables, brewing stands, etc.)
        if (state.is(Tags.Blocks.PLAYER_WORKSTATIONS_CRAFTING_TABLES)
                || state.is(Tags.Blocks.PLAYER_WORKSTATIONS_FURNACES)
                || state.is(BlockTags.ANVIL)
                || state.is(BlockTags.BEDS)
                || isWorkstationBlock(block)) {
            return BlockCategory.WORKSTATION;
        }

        // 4. Utility / Lighting / Mechanisms
        if (isUtilityBlock(block, state)) {
            return BlockCategory.UTILITY;
        }

        // 5. Environmental Hazards
        if (isHazardBlock(block, state)) {
            return BlockCategory.HAZARD;
        }

        // 6. Generic Block Entity fallback (e.g. modded machines, containers)
        if (state.hasBlockEntity()) {
            return BlockCategory.CONTAINER;
        }

        // 7. Natural filler exclusion
        if (isNaturalFiller(block, state)) {
            return BlockCategory.NONE;
        }

        // 8. Building Materials
        if (isBuildingBlock(block, state)) {
            return BlockCategory.BUILDING;
        }

        return BlockCategory.OTHER;
    }

    private static boolean isOreBlock(Block block) {
        return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.NETHER_QUARTZ_ORE || block == Blocks.ANCIENT_DEBRIS
                || block == Blocks.RAW_IRON_BLOCK || block == Blocks.RAW_COPPER_BLOCK || block == Blocks.RAW_GOLD_BLOCK;
    }

    private static boolean isContainerBlock(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
                || block == Blocks.BARREL || block == Blocks.ENDER_CHEST
                || block == Blocks.DISPENSER || block == Blocks.DROPPER || block == Blocks.HOPPER
                || block == Blocks.CHISELED_BOOKSHELF;
    }

    private static boolean isWorkstationBlock(Block block) {
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER
                || block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL
                || block == Blocks.SMITHING_TABLE || block == Blocks.FLETCHING_TABLE
                || block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.LOOM
                || block == Blocks.STONECUTTER || block == Blocks.GRINDSTONE
                || block == Blocks.BREWING_STAND || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.CAULDRON || block == Blocks.WATER_CAULDRON
                || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON
                || block == Blocks.RESPAWN_ANCHOR;
    }

    private static boolean isUtilityBlock(Block block, BlockState state) {
        return block == Blocks.TORCH || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.LANTERN || block == Blocks.SOUL_LANTERN
                || block == Blocks.GLOWSTONE || block == Blocks.SEA_LANTERN || block == Blocks.SHROOMLIGHT
                || block == Blocks.OCHRE_FROGLIGHT || block == Blocks.VERDANT_FROGLIGHT || block == Blocks.PEARLESCENT_FROGLIGHT
                || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR
                || block == Blocks.LEVER || state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || block == Blocks.SPAWNER || block == Blocks.BEACON || block == Blocks.CONDUIT
                || block == Blocks.BELL || block == Blocks.LODESTONE || block == Blocks.LIGHTNING_ROD
                || block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN;
    }

    private static boolean isHazardBlock(Block block, BlockState state) {
        return block == Blocks.LAVA
                || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.WITHER_ROSE
                || block == Blocks.TNT
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.CACTUS
                || block == Blocks.POWDER_SNOW;
    }

    private static boolean isBuildingBlock(Block block, BlockState state) {
        return state.is(Tags.Blocks.COBBLESTONES)
                || block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE || block == Blocks.COBBLED_DEEPSLATE
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOOL) || state.is(Tags.Blocks.CONCRETES)
                || state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)
                || block == Blocks.BRICKS || block == Blocks.STONE_BRICKS || block == Blocks.MUD_BRICKS || block == Blocks.NETHER_BRICKS;
    }
}
