package com.alcyone.jarvis.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Scans, identifies, and inspects modded machinery, energy generators, and processing units
 * around Jarvis or the player.
 */
public class MachineScanner {

    public static JsonArray scanMachines(ServerLevel level, BlockPos origin, int radius) {
        JsonArray machines = new JsonArray();
        if (level == null || origin == null) {
            return machines;
        }

        int actualRadius = Math.max(4, Math.min(radius, 48));
        BlockPos min = origin.offset(-actualRadius, -actualRadius, -actualRadius);
        BlockPos max = origin.offset(actualRadius, actualRadius, actualRadius);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) continue;

            BlockState state = level.getBlockState(pos);
            ResourceLocation blockLoc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String namespace = blockLoc.getNamespace();

            // Focus on modded blocks or functional vanilla machines
            boolean isVanillaMachine = namespace.equals("minecraft") && (
                    blockLoc.getPath().contains("furnace") ||
                    blockLoc.getPath().contains("smoker") ||
                    blockLoc.getPath().contains("brewing") ||
                    blockLoc.getPath().contains("crafter")
            );
            boolean isModded = !namespace.equals("minecraft");

            if (!isModded && !isVanillaMachine) {
                continue;
            }

            // Inspect capabilities: Energy, Fluids, Items
            IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
            IFluidHandler fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);

            // If it has none of these capabilities and isn't a known machine, skip decoration block entities
            if (energy == null && fluid == null && items == null && !isVanillaMachine) {
                continue;
            }

            JsonObject mJson = new JsonObject();
            mJson.addProperty("block_id", blockLoc.toString());
            mJson.addProperty("name", state.getBlock().getName().getString());
            mJson.addProperty("mod", namespace);

            JsonObject posObj = new JsonObject();
            posObj.addProperty("x", pos.getX());
            posObj.addProperty("y", pos.getY());
            posObj.addProperty("z", pos.getZ());
            mJson.add("pos", posObj);

            double dist = Math.sqrt(origin.distSqr(pos));
            mJson.addProperty("distance", Math.round(dist * 10.0) / 10.0);

            // Energy Telemetry
            if (energy != null) {
                JsonObject energyJson = new JsonObject();
                energyJson.addProperty("stored", energy.getEnergyStored());
                energyJson.addProperty("capacity", energy.getMaxEnergyStored());
                energyJson.addProperty("unit", "FE");
                mJson.add("energy", energyJson);
            }

            // Fluid Telemetry
            if (fluid != null) {
                JsonArray fluidTanks = new JsonArray();
                for (int t = 0; t < fluid.getTanks(); t++) {
                    JsonObject tankJson = new JsonObject();
                    var fluidStack = fluid.getFluidInTank(t);
                    tankJson.addProperty("tank_index", t);
                    tankJson.addProperty("amount", fluidStack.getAmount());
                    tankJson.addProperty("capacity", fluid.getTankCapacity(t));
                    tankJson.addProperty("fluid", fluidStack.isEmpty() ? "empty" : BuiltInRegistries.FLUID.getKey(fluidStack.getFluid()).toString());
                    fluidTanks.add(tankJson);
                }
                mJson.add("fluids", fluidTanks);
            }

            // Item inventory summary
            if (items != null) {
                int totalItems = 0;
                int filledSlots = 0;
                for (int s = 0; s < items.getSlots(); s++) {
                    var stack = items.getStackInSlot(s);
                    if (!stack.isEmpty()) {
                        filledSlots++;
                        totalItems += stack.getCount();
                    }
                }
                JsonObject itemsJson = new JsonObject();
                itemsJson.addProperty("slots_total", items.getSlots());
                itemsJson.addProperty("slots_filled", filledSlots);
                itemsJson.addProperty("item_count", totalItems);
                mJson.add("inventory", itemsJson);
            }

            machines.add(mJson);
        }

        return machines;
    }
}
