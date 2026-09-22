package com.alcyone.jarvis.tech;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FTB Direwolf20 Modded Tech & Energy Logistics Engine for Jarvis Companion.
 * Interfaces with Mekanism, Thermal Expansion, EnderIO, and other modded machinery
 * via standard NeoForge Block Capabilities (ItemHandler & EnergyStorage).
 * Enables autonomous Ore Multiplication (Enrichment Chamber, Crusher, Pulverizer, Induction Smelter).
 */
public class ModdedTechManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    public record MachineCandidate(
            BlockPos pos,
            String modId,
            String blockId,
            String machineName,
            boolean isProcessor,
            boolean isSmelter,
            int energyStored,
            int maxEnergy,
            double distSq
    ) {}

    /**
     * Scans for modded machines in the area with energy and item handler capabilities.
     */
    public static List<MachineCandidate> findTechMachines(ServerLevel level, BlockPos center, int radius) {
        List<MachineCandidate> list = new ArrayList<>();
        if (level == null || center == null) return list;

        int rad = Math.max(4, Math.min(radius, 48));
        BlockPos min = center.offset(-rad, -rad, -rad);
        BlockPos max = center.offset(rad, rad, rad);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos)) continue;

            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) continue;

            BlockState state = level.getBlockState(pos);
            ResourceLocation blockLoc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String namespace = blockLoc.getNamespace().toLowerCase();
            String path = blockLoc.getPath().toLowerCase();

            // Check if it's a recognized tech mod or has energy storage
            boolean isKnownTechMod = namespace.contains("mekanism")
                    || namespace.contains("thermal")
                    || namespace.contains("enderio")
                    || namespace.contains("actuallyadditions")
                    || namespace.contains("industrialforegoing")
                    || namespace.contains("modern_industrialization");

            IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
            if (energy == null) {
                energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
            }

            if (!isKnownTechMod && energy == null) continue;

            // Must have item handler
            IItemHandler itemHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
            if (itemHandler == null) {
                itemHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            }
            if (itemHandler == null) continue;

            int energyStored = energy != null ? energy.getEnergyStored() : 0;
            int maxEnergy = energy != null ? energy.getMaxEnergyStored() : 0;

            boolean isProcessor = path.contains("enrich") || path.contains("crush") || path.contains("pulver")
                    || path.contains("sag_mill") || path.contains("grind") || path.contains("mill");
            boolean isSmelter = path.contains("smelt") || path.contains("furnace") || path.contains("alloy");

            list.add(new MachineCandidate(
                    pos.immutable(),
                    namespace,
                    blockLoc.toString(),
                    state.getBlock().getName().getString(),
                    isProcessor,
                    isSmelter,
                    energyStored,
                    maxEnergy,
                    center.distSqr(pos)
            ));
        }

        // Rank: Processors with energy first, then smelters, sorted by distance
        list.sort(Comparator.comparing((MachineCandidate m) -> !m.isProcessor)
                .thenComparing((MachineCandidate m) -> m.energyStored <= 0)
                .thenComparingDouble(MachineCandidate::distSq));

        return list;
    }

    /**
     * Autonomous Modded Ore Multiplication Cycle:
     * Feeds raw ores into high-efficiency machines (Enrichment Chamber, Pulverizer, SAG Mill, Induction Smelter)
     * and collects processed products into Jarvis's inventory.
     */
    public static JsonObject processOres(ServerLevel level, JarvisCompanionEntity companion,
                                        BlockPos searchCenter, int radius, String oreFilter) {
        JsonObject result = new JsonObject();
        if (level == null || companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        List<MachineCandidate> machines = findTechMachines(level, center, radius);

        if (machines.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "No powered modded machines found within radius " + radius);
            return result;
        }

        ItemStackHandler inv = companion.getInventory();
        int totalInserted = 0;
        int totalExtracted = 0;
        JsonArray machineReports = new JsonArray();

        for (MachineCandidate machine : machines) {
            BlockPos pos = machine.pos();

            // Check item capabilities from multiple sides (UP for input, DOWN/SIDES for output)
            IItemHandler topHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
            IItemHandler nullHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            IItemHandler downHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);

            IItemHandler inputHandler = topHandler != null ? topHandler : nullHandler;
            IItemHandler outputHandler = downHandler != null ? downHandler : (nullHandler != null ? nullHandler : topHandler);

            if (inputHandler == null) continue;

            int machineInserted = 0;
            int machineExtracted = 0;

            // 1. Collect finished products from machine output slots
            if (outputHandler != null) {
                for (int slot = 0; slot < outputHandler.getSlots(); slot++) {
                    ItemStack inSlot = outputHandler.getStackInSlot(slot);
                    if (!inSlot.isEmpty() && isProcessedProduct(inSlot)) {
                        ItemStack extracted = outputHandler.extractItem(slot, inSlot.getCount(), false);
                        if (!extracted.isEmpty()) {
                            machineExtracted += extracted.getCount();
                            totalExtracted += extracted.getCount();
                            addItemsToInventory(inv, extracted);
                        }
                    }
                }
            }

            // 2. Feed raw ores into machine input slots
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (isRawOre(stack, oreFilter)) {
                    ItemStack toInsert = stack.copy();
                    for (int slot = 0; slot < inputHandler.getSlots() && !toInsert.isEmpty(); slot++) {
                        ItemStack remainder = inputHandler.insertItem(slot, toInsert, false);
                        int accepted = toInsert.getCount() - remainder.getCount();
                        if (accepted > 0) {
                            inv.extractItem(i, accepted, false);
                            machineInserted += accepted;
                            totalInserted += accepted;
                            toInsert = remainder;
                        }
                    }
                }
            }

            if (machineInserted > 0 || machineExtracted > 0) {
                JsonObject mObj = new JsonObject();
                mObj.addProperty("machine", machine.blockId());
                mObj.addProperty("name", machine.machineName());
                mObj.addProperty("x", pos.getX());
                mObj.addProperty("y", pos.getY());
                mObj.addProperty("z", pos.getZ());
                mObj.addProperty("energy_fe", machine.energyStored());
                mObj.addProperty("max_energy_fe", machine.maxEnergy());
                mObj.addProperty("inserted", machineInserted);
                mObj.addProperty("extracted", machineExtracted);
                machineReports.add(mObj);
            }
        }

        if (totalInserted > 0 || totalExtracted > 0) {
            level.playSound(null, center, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F, 1.2F);
        }

        result.addProperty("success", true);
        result.addProperty("total_ores_inserted", totalInserted);
        result.addProperty("total_items_extracted", totalExtracted);
        result.addProperty("machines_utilized", machineReports.size());
        result.add("machines", machineReports);

        return result;
    }

    /**
     * Scans and returns detailed status of all nearby modded tech machines.
     */
    public static JsonObject scanMachines(ServerLevel level, BlockPos center, int radius) {
        JsonObject result = new JsonObject();
        if (level == null || center == null) {
            result.addProperty("success", false);
            result.addProperty("error", "Invalid level or center position");
            return result;
        }

        List<MachineCandidate> machines = findTechMachines(level, center, radius);
        JsonArray arr = new JsonArray();
        for (MachineCandidate m : machines) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", m.blockId());
            obj.addProperty("name", m.machineName());
            obj.addProperty("mod", m.modId());
            obj.addProperty("x", m.pos().getX());
            obj.addProperty("y", m.pos().getY());
            obj.addProperty("z", m.pos().getZ());
            obj.addProperty("is_processor", m.isProcessor());
            obj.addProperty("is_smelter", m.isSmelter());
            obj.addProperty("energy_fe", m.energyStored());
            obj.addProperty("max_energy_fe", m.maxEnergy());
            obj.addProperty("distance", Math.sqrt(m.distSq()));
            arr.add(obj);
        }

        result.addProperty("success", true);
        result.addProperty("count", machines.size());
        result.add("machines", arr);

        return result;
    }

    private static boolean isRawOre(ItemStack stack, String filter) {
        if (stack == null || stack.isEmpty()) return false;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        boolean isRaw = path.startsWith("raw_") || path.endsWith("_ore") || path.contains("ancient_debris");
        if (!isRaw) return false;
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter) || "ores".equalsIgnoreCase(filter)) {
            return true;
        }
        return path.contains(filter.toLowerCase());
    }

    private static boolean isProcessedProduct(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        return path.endsWith("_ingot") || path.endsWith("_dust") || path.endsWith("_crystal")
                || path.endsWith("_shard") || path.endsWith("_clump") || path.endsWith("_gem")
                || path.equals("iron_ingot") || path.equals("gold_ingot") || path.equals("copper_ingot")
                || path.equals("diamond") || path.equals("netherite_scrap") || path.equals("redstone");
    }

    private static void addItemsToInventory(ItemStackHandler inv, ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = 0; i < inv.getSlots() && !remainder.isEmpty(); i++) {
            remainder = inv.insertItem(i, remainder, false);
        }
    }
}
