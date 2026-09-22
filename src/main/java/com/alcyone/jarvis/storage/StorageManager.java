package com.alcyone.jarvis.storage;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-performance Storage & Smart Logistics Engine for the Jarvis Companion.
 * Fully native to NeoForge capabilities (IItemHandler / IItemHandlerModifiable)
 * with zero item voiding and native support for Vanilla, Sophisticated Storage,
 * Functional Storage, and Iron Chests: Restocked.
 */
public class StorageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    public static final List<BlockPos> markedTargetPositions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static JsonArray getMarkedPositionsJson() {
        JsonArray arr = new JsonArray();
        for (BlockPos p : markedTargetPositions) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", p.getX());
            obj.addProperty("y", p.getY());
            obj.addProperty("z", p.getZ());
            arr.add(obj);
        }
        return arr;
    }

    public static void clearMarkers() {
        markedTargetPositions.clear();
    }

    public static boolean toggleMarker(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (markedTargetPositions.contains(pos)) {
            markedTargetPositions.remove(pos);
            if (player != null) {
                player.displayClientMessage(Component.literal("§c[Jarvis] Hedef sandik konumu kaldirildi: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), true);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.05);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
            }
            return false;
        } else {
            markedTargetPositions.add(pos);
            int idx = markedTargetPositions.size();
            if (player != null) {
                player.displayClientMessage(Component.literal("§a[Jarvis] #" + idx + " Hedef sandik konumu eklendi: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), true);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0f, 1.4f);
            }
            return true;
        }
    }

    /**
     * Resolves an IItemHandler capability for a block at the given position.
     * Checks side == null first (unifies double chests), then iterates Direction.values(),
     * and falls back to InvWrapper if the block entity implements Container.
     */
    public static IItemHandler getItemHandler(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) {
            return null;
        }

        // 1. Un-sided capability (preferred for chests, double chests, modded inventories)
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            return handler;
        }

        // 2. Sided capability fallback
        for (Direction side : Direction.values()) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler != null) {
                return handler;
            }
        }

        // 3. Vanilla Container fallback wrapped with InvWrapper
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return new InvWrapper(container);
        }

        return null;
    }

    /**
     * Marks the block entity dirty and ensures changes are persisted.
     */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.setChanged();
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            Direction connected = ChestBlock.getConnectedDirection(state);
            if (connected != null) {
                BlockEntity other = level.getBlockEntity(pos.relative(connected));
                if (other != null) {
                    other.setChanged();
                }
            }
        }
    }

    /**
     * In-place compacting & sorting of a container at pos.
     * Merges duplicate partial stacks and sorts by Direwolf20 category hierarchy.
     */
    public static JsonObject sortContainer(ServerLevel level, BlockPos pos) {
        JsonObject result = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        result.add("pos", posObj);

        IItemHandler handler = getItemHandler(level, pos);
        if (handler == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No container found at " + pos.toShortString());
            return result;
        }

        int totalSlots = handler.getSlots();
        if (totalSlots <= 0) {
            result.addProperty("success", false);
            result.addProperty("error", "Container has 0 slots");
            return result;
        }

        // Step 1: Read all non-empty items
        List<ItemStack> rawItems = new ArrayList<>();
        int totalItemCount = 0;
        for (int slot = 0; slot < totalSlots; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                rawItems.add(stack.copy());
                totalItemCount += stack.getCount();
            }
        }

        if (rawItems.isEmpty()) {
            result.addProperty("success", true);
            result.addProperty("slots", totalSlots);
            result.addProperty("item_count", 0);
            result.addProperty("distinct_items", 0);
            result.addProperty("dominant_category", "Empty");
            result.addProperty("message", "Container is empty");
            return result;
        }

        // Step 2: Compacting - merge duplicate partial stacks
        List<ItemStack> compacted = new ArrayList<>();
        for (ItemStack item : rawItems) {
            boolean merged = false;
            for (ItemStack existing : compacted) {
                if (ItemStack.isSameItemSameComponents(existing, item) && existing.getCount() < existing.getMaxStackSize()) {
                    int canAdd = existing.getMaxStackSize() - existing.getCount();
                    int toAdd = Math.min(canAdd, item.getCount());
                    existing.grow(toAdd);
                    item.shrink(toAdd);
                    if (item.isEmpty()) {
                        merged = true;
                        break;
                    }
                }
            }
            if (!item.isEmpty()) {
                compacted.add(item);
            }
        }

        // Step 3: Sort compacted items
        compacted.sort((a, b) -> {
            ItemCategory catA = ItemCategory.classify(a);
            ItemCategory catB = ItemCategory.classify(b);
            if (catA != catB) {
                return Integer.compare(catA.getPriority(), catB.getPriority());
            }
            String idA = BuiltInRegistries.ITEM.getKey(a.getItem()).toString();
            String idB = BuiltInRegistries.ITEM.getKey(b.getItem()).toString();
            int idCmp = idA.compareTo(idB);
            if (idCmp != 0) return idCmp;
            return Integer.compare(b.getCount(), a.getCount());
        });

        // Step 4: Write back safely with zero item voiding
        if (handler instanceof IItemHandlerModifiable modifiable) {
            for (int i = 0; i < totalSlots; i++) {
                modifiable.setStackInSlot(i, ItemStack.EMPTY);
            }
            for (int i = 0; i < compacted.size() && i < totalSlots; i++) {
                modifiable.setStackInSlot(i, compacted.get(i));
            }
            // Zero-voiding fallback: if compacted exceeds slots, insert remaining
            for (int i = totalSlots; i < compacted.size(); i++) {
                ItemStack remainder = ItemHandlerHelper.insertItem(modifiable, compacted.get(i), false);
                if (!remainder.isEmpty()) {
                    net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, remainder);
                }
            }
        } else {
            // Extract all items until empty
            for (int i = 0; i < totalSlots; i++) {
                while (!handler.getStackInSlot(i).isEmpty()) {
                    ItemStack ex = handler.extractItem(i, handler.getSlotLimit(i), false);
                    if (ex.isEmpty()) break;
                }
            }
            // Insert sorted stacks
            for (ItemStack stack : compacted) {
                ItemStack remainder = ItemHandlerHelper.insertItem(handler, stack, false);
                if (!remainder.isEmpty()) {
                    net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, remainder);
                }
            }
        }

        markDirty(level, pos);

        // Calculate dominant category
        Map<ItemCategory, Integer> categoryCounts = new EnumMap<>(ItemCategory.class);
        for (ItemStack st : compacted) {
            ItemCategory cat = ItemCategory.classify(st);
            categoryCounts.put(cat, categoryCounts.getOrDefault(cat, 0) + st.getCount());
        }
        ItemCategory dominant = ItemCategory.MISCELLANEOUS;
        int maxCount = -1;
        for (Map.Entry<ItemCategory, Integer> entry : categoryCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                dominant = entry.getKey();
            }
        }

        result.addProperty("success", true);
        result.addProperty("slots", totalSlots);
        result.addProperty("item_count", totalItemCount);
        result.addProperty("distinct_items", compacted.size());
        result.addProperty("dominant_category", dominant.getDisplayName());
        return result;
    }

    /**
     * Scans for containers within a radius around center, categorizing each.
     */
    public static JsonObject scanContainers(ServerLevel level, BlockPos center, int rawRadius) {
        int radius = Math.max(1, Math.min(rawRadius <= 0 ? 16 : rawRadius, 64));
        JsonObject result = new JsonObject();
        JsonObject centerObj = new JsonObject();
        centerObj.addProperty("x", center.getX());
        centerObj.addProperty("y", center.getY());
        centerObj.addProperty("z", center.getZ());
        result.add("center", centerObj);
        result.addProperty("radius", radius);

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        Set<BlockPos> processedChests = new HashSet<>();
        JsonArray containersArray = new JsonArray();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockPos bPos : chunk.getBlockEntitiesPos()) {
                    if (Math.abs(bPos.getX() - center.getX()) > radius
                            || Math.abs(bPos.getY() - center.getY()) > radius
                            || Math.abs(bPos.getZ() - center.getZ()) > radius) {
                        continue;
                    }

                    if (processedChests.contains(bPos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(bPos);
                    // Prevent counting double chest twice
                    if (state.getBlock() instanceof ChestBlock) {
                        Direction connected = ChestBlock.getConnectedDirection(state);
                        if (connected != null) {
                            BlockPos otherPos = bPos.relative(connected);
                            if (processedChests.contains(otherPos)) {
                                continue;
                            }
                            processedChests.add(otherPos);
                        }
                    }

                    IItemHandler handler = getItemHandler(level, bPos);
                    if (handler == null || handler.getSlots() <= 0) {
                        continue;
                    }

                    processedChests.add(bPos);

                    int totalSlots = handler.getSlots();
                    int occupied = 0;
                    int totalItems = 0;
                    Map<ItemCategory, Integer> categoryCounts = new EnumMap<>(ItemCategory.class);

                    for (int s = 0; s < totalSlots; s++) {
                        ItemStack stack = handler.getStackInSlot(s);
                        if (!stack.isEmpty()) {
                            occupied++;
                            totalItems += stack.getCount();
                            ItemCategory cat = ItemCategory.classify(stack);
                            categoryCounts.put(cat, categoryCounts.getOrDefault(cat, 0) + stack.getCount());
                        }
                    }

                    String dominantCategory = "Empty";
                    if (occupied > 0) {
                        ItemCategory dominant = ItemCategory.MISCELLANEOUS;
                        int maxC = -1;
                        for (Map.Entry<ItemCategory, Integer> entry : categoryCounts.entrySet()) {
                            if (entry.getValue() > maxC) {
                                maxC = entry.getValue();
                                dominant = entry.getKey();
                            }
                        }
                        dominantCategory = dominant.getDisplayName();
                    }

                    JsonObject cObj = new JsonObject();
                    JsonObject pos = new JsonObject();
                    pos.addProperty("x", bPos.getX());
                    pos.addProperty("y", bPos.getY());
                    pos.addProperty("z", bPos.getZ());
                    cObj.add("pos", pos);
                    cObj.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    cObj.addProperty("slots", totalSlots);
                    cObj.addProperty("occupied_slots", occupied);
                    cObj.addProperty("free_slots", totalSlots - occupied);
                    cObj.addProperty("item_count", totalItems);
                    cObj.addProperty("category", dominantCategory);
                    containersArray.add(cObj);
                }
            }
        }

        result.addProperty("success", true);
        result.addProperty("count", containersArray.size());
        result.add("containers", containersArray);
        return result;
    }

    /**
     * Safely transfers items matching a category filter between two containers.
     * Zero item voiding guaranteed.
     */
    public static JsonObject transferItems(ServerLevel level, BlockPos sourcePos, BlockPos targetPos, String filter) {
        JsonObject result = new JsonObject();
        JsonObject srcObj = new JsonObject();
        srcObj.addProperty("x", sourcePos.getX());
        srcObj.addProperty("y", sourcePos.getY());
        srcObj.addProperty("z", sourcePos.getZ());
        result.add("source", srcObj);

        JsonObject tgtObj = new JsonObject();
        tgtObj.addProperty("x", targetPos.getX());
        tgtObj.addProperty("y", targetPos.getY());
        tgtObj.addProperty("z", targetPos.getZ());
        result.add("target", tgtObj);

        IItemHandler sourceHandler = getItemHandler(level, sourcePos);
        if (sourceHandler == null) {
            result.addProperty("success", false);
            result.addProperty("error", "Source container not found at " + sourcePos.toShortString());
            return result;
        }

        IItemHandler targetHandler = getItemHandler(level, targetPos);
        if (targetHandler == null) {
            result.addProperty("success", false);
            result.addProperty("error", "Target container not found at " + targetPos.toShortString());
            return result;
        }

        String filterStr = (filter == null || filter.isBlank()) ? "all" : filter.trim().toLowerCase();
        result.addProperty("filter", filterStr);

        int totalTransferred = 0;

        for (int slot = 0; slot < sourceHandler.getSlots(); slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;

            if (!matchesFilter(inSlot, filterStr)) {
                continue;
            }

            // Test extraction
            ItemStack extractedSim = sourceHandler.extractItem(slot, inSlot.getCount(), true);
            if (extractedSim.isEmpty()) continue;

            // Test insertion into target
            ItemStack remainingSim = ItemHandlerHelper.insertItem(targetHandler, extractedSim, true);
            int canMove = extractedSim.getCount() - remainingSim.getCount();
            if (canMove <= 0) continue;

            // Actual extraction & insertion
            ItemStack actuallyExtracted = sourceHandler.extractItem(slot, canMove, false);
            if (!actuallyExtracted.isEmpty()) {
                ItemStack leftover = ItemHandlerHelper.insertItem(targetHandler, actuallyExtracted, false);
                // Zero-voiding safeguard
                if (!leftover.isEmpty()) {
                    leftover = ItemHandlerHelper.insertItem(sourceHandler, leftover, false);
                    if (!leftover.isEmpty()) {
                        net.minecraft.world.Containers.dropItemStack(level, sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 0.5, leftover);
                    }
                }
                totalTransferred += (actuallyExtracted.getCount() - leftover.getCount());
            }
        }

        markDirty(level, sourcePos);
        markDirty(level, targetPos);

        result.addProperty("success", true);
        result.addProperty("transferred", totalTransferred);
        return result;
    }

    /**
     * Retrieves up to count of itemId from nearby chests, places into companion inventory,
     * navigates to player and delivers to player inventory.
     */
    public static JsonObject retrieveItem(ServerLevel level, BlockPos searchCenter, JarvisCompanionEntity companion,
                                         ServerPlayer targetPlayer, String itemId, int requestedCount) {
        JsonObject result = new JsonObject();
        result.addProperty("item", itemId);
        result.addProperty("requested", requestedCount);

        if (companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        int countNeeded = Math.max(1, requestedCount);
        int retrieved = 0;
        BlockPos foundPos = null;

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();
        int radius = 32;

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        Set<BlockPos> processed = new HashSet<>();

        outer:
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockPos bPos : chunk.getBlockEntitiesPos()) {
                    if (Math.abs(bPos.getX() - center.getX()) > radius
                            || Math.abs(bPos.getY() - center.getY()) > radius
                            || Math.abs(bPos.getZ() - center.getZ()) > radius) {
                        continue;
                    }
                    if (processed.contains(bPos)) continue;

                    IItemHandler handler = getItemHandler(level, bPos);
                    if (handler == null) continue;
                    processed.add(bPos);

                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (stack.isEmpty()) continue;

                        if (matchesItemId(stack, itemId)) {
                            int toExtract = Math.min(countNeeded - retrieved, stack.getCount());

                            // Check if companion inventory has space
                            ItemStack testStack = stack.copyWithCount(toExtract);
                            ItemStack companionRemaining = ItemHandlerHelper.insertItem(companion.getInventory(), testStack, true);
                            int canFitInCompanion = toExtract - companionRemaining.getCount();

                            if (canFitInCompanion > 0) {
                                ItemStack extracted = handler.extractItem(slot, canFitInCompanion, false);
                                if (!extracted.isEmpty()) {
                                    ItemStack leftover = ItemHandlerHelper.insertItem(companion.getInventory(), extracted, false);
                                    if (!leftover.isEmpty()) {
                                        ItemHandlerHelper.insertItem(handler, leftover, false);
                                    }
                                    int moved = extracted.getCount() - leftover.getCount();
                                    retrieved += moved;
                                    foundPos = bPos;
                                    markDirty(level, bPos);

                                    if (retrieved >= countNeeded) {
                                        break outer;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        result.addProperty("retrieved", retrieved);

        int delivered = 0;
        if (targetPlayer != null && retrieved > 0) {
            companion.teleportNearPlayer(targetPlayer);
            companion.setFollowTarget(targetPlayer, 2.5D);

            // Transfer retrieved items from companion inventory to target player
            ItemStackHandler inv = companion.getInventory();
            int toDeliver = retrieved;
            for (int i = 0; i < inv.getSlots() && toDeliver > 0; i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty() && matchesItemId(stack, itemId)) {
                    int takeCount = Math.min(toDeliver, stack.getCount());
                    ItemStack deliverStack = stack.split(takeCount);
                    int deliverAmount = deliverStack.getCount();

                    boolean added = targetPlayer.getInventory().add(deliverStack);
                    if (!deliverStack.isEmpty()) {
                        // Drop at player's feet if player inventory is full
                        targetPlayer.drop(deliverStack.copy(), false);
                        deliverStack.setCount(0);
                    }
                    delivered += deliverAmount;
                    toDeliver -= deliverAmount;

                    if (stack.isEmpty()) {
                        inv.setStackInSlot(i, ItemStack.EMPTY);
                    } else {
                        inv.setStackInSlot(i, stack);
                    }
                }
            }

            level.playSound(null, targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
        }

        result.addProperty("delivered", delivered);
        result.addProperty("success", retrieved > 0);
        if (foundPos != null) {
            JsonObject srcObj = new JsonObject();
            srcObj.addProperty("x", foundPos.getX());
            srcObj.addProperty("y", foundPos.getY());
            srcObj.addProperty("z", foundPos.getZ());
            result.add("source_container", srcObj);
        }

        return result;
    }

    /**
     * Deposits items from companion's 27-slot internal inventory into nearby categorized or empty chests.
     */
    public static JsonObject depositLoot(ServerLevel level, JarvisCompanionEntity companion, BlockPos searchCenter,
                                        int rawRadius, String filter) {
        JsonObject result = new JsonObject();
        int radius = Math.max(8, Math.min(rawRadius <= 0 ? 32 : rawRadius, 64));
        String filterStr = (filter == null || filter.isBlank()) ? "all" : filter.trim().toLowerCase();
        result.addProperty("filter", filterStr);
        result.addProperty("radius", radius);

        if (companion == null || !companion.isAlive()) {
            result.addProperty("success", false);
            result.addProperty("error", "Companion is not spawned or active");
            return result;
        }

        BlockPos center = searchCenter != null ? searchCenter : companion.blockPosition();

        // Find and classify all nearby containers
        List<ContainerCandidate> candidates = findContainers(level, center, radius);
        if (candidates.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "No containers found within radius " + radius);
            return result;
        }

        int totalDeposited = 0;
        Set<BlockPos> chestsUsed = new HashSet<>();
        ItemStackHandler inv = companion.getInventory();

        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            if (!matchesFilter(stack, filterStr)) {
                continue;
            }

            ItemCategory itemCategory = ItemCategory.classify(stack);

            while (!stack.isEmpty()) {
                // Prioritize matching categorized chests first, then empty chests, then any chest that can accept
                ContainerCandidate bestTarget = null;
                for (ContainerCandidate cand : candidates) {
                    if (cand.category == itemCategory && cand.canAccept(stack)) {
                        bestTarget = cand;
                        break;
                    }
                }
                if (bestTarget == null) {
                    for (ContainerCandidate cand : candidates) {
                        if (cand.isEmpty() && cand.canAccept(stack)) {
                            bestTarget = cand;
                            break;
                        }
                    }
                }
                if (bestTarget == null) {
                    for (ContainerCandidate cand : candidates) {
                        if (cand.canAccept(stack)) {
                            bestTarget = cand;
                            break;
                        }
                    }
                }

                if (bestTarget == null) {
                    break;
                }

                int prevCount = stack.getCount();
                ItemStack leftover = ItemHandlerHelper.insertItem(bestTarget.handler, stack, false);
                int moved = prevCount - leftover.getCount();
                if (moved > 0) {
                    totalDeposited += moved;
                    chestsUsed.add(bestTarget.pos);
                    markDirty(level, bestTarget.pos);
                    bestTarget.updateSlotCounts();
                    stack = leftover;
                    inv.setStackInSlot(slot, stack);
                } else {
                    break;
                }
            }
        }

        result.addProperty("success", true);
        result.addProperty("deposited", totalDeposited);

        JsonArray usedArray = new JsonArray();
        for (BlockPos p : chestsUsed) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("x", p.getX());
            pObj.addProperty("y", p.getY());
            pObj.addProperty("z", p.getZ());
            usedArray.add(pObj);
        }
        result.add("chests_used", usedArray);

        return result;
    }

    public static class ContainerCandidate {
        public final BlockPos pos;
        public final IItemHandler handler;
        public ItemCategory category;
        public int occupiedSlots;
        public int totalSlots;

        public final ServerLevel level;

        public ContainerCandidate(BlockPos pos, IItemHandler handler) {
            this(pos, handler, null);
        }

        public ContainerCandidate(BlockPos pos, IItemHandler handler, ServerLevel level) {
            this.pos = pos;
            this.handler = handler;
            this.level = level;
            updateSlotCounts();
        }

        public void updateSlotCounts() {
            this.totalSlots = handler.getSlots();
            this.occupiedSlots = 0;

            if (this.level != null) {
                ItemCategory signCat = getSignCategory(this.level, this.pos);
                if (signCat != null) {
                    this.category = signCat;
                    for (int s = 0; s < totalSlots; s++) {
                        if (!handler.getStackInSlot(s).isEmpty()) {
                            this.occupiedSlots++;
                        }
                    }
                    return;
                }
            }

            Map<ItemCategory, Integer> catCounts = new EnumMap<>(ItemCategory.class);
            for (int s = 0; s < totalSlots; s++) {
                ItemStack st = handler.getStackInSlot(s);
                if (!st.isEmpty()) {
                    this.occupiedSlots++;
                    ItemCategory c = ItemCategory.classify(st);
                    catCounts.put(c, catCounts.getOrDefault(c, 0) + st.getCount());
                }
            }
            if (occupiedSlots == 0) {
                this.category = ItemCategory.MISCELLANEOUS;
            } else {
                ItemCategory dom = ItemCategory.MISCELLANEOUS;
                int max = -1;
                for (Map.Entry<ItemCategory, Integer> e : catCounts.entrySet()) {
                    if (e.getValue() > max) {
                        max = e.getValue();
                        dom = e.getKey();
                    }
                }
                this.category = dom;
            }
        }

        public boolean isEmpty() {
            return occupiedSlots == 0;
        }

        public boolean hasFreeSlots() {
            return occupiedSlots < totalSlots;
        }

        public boolean canAccept(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            ItemStack sim = ItemHandlerHelper.insertItem(handler, stack, true);
            return sim.getCount() < stack.getCount();
        }
    }

    public static List<ContainerCandidate> findContainers(ServerLevel level, BlockPos center, int radius) {
        List<ContainerCandidate> list = new ArrayList<>();
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        Set<BlockPos> processed = new HashSet<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockPos bPos : chunk.getBlockEntitiesPos()) {
                    if (Math.abs(bPos.getX() - center.getX()) > radius
                            || Math.abs(bPos.getY() - center.getY()) > radius
                            || Math.abs(bPos.getZ() - center.getZ()) > radius) {
                        continue;
                    }
                    if (processed.contains(bPos)) continue;

                    BlockState state = level.getBlockState(bPos);
                    BlockEntity be = level.getBlockEntity(bPos);
                    if (!isStorageContainer(state, be)) {
                        continue;
                    }

                    // Never touch or empty farm, automation, or player-protected containers!
                    if (isAutomationOrFarmContainer(level, bPos, be)) {
                        continue;
                    }

                    if (state.getBlock() instanceof ChestBlock) {
                        Direction connected = ChestBlock.getConnectedDirection(state);
                        if (connected != null) {
                            BlockPos other = bPos.relative(connected);
                            if (processed.contains(other)) continue;
                            processed.add(other);
                        }
                    }

                    IItemHandler handler = getItemHandler(level, bPos);
                    if (handler != null && handler.getSlots() > 0) {
                        processed.add(bPos);
                        list.add(new ContainerCandidate(bPos, handler, level));
                    }
                }
            }
        }
        return list;
    }

    public static boolean isUnderRoof(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!level.canSeeSky(pos)) return true;
        for (int dy = 1; dy <= 14; dy++) {
            BlockState aboveState = level.getBlockState(pos.above(dy));
            if (!aboveState.isAir() && !aboveState.canBeReplaced() && aboveState.isSolid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a container is part of an automated farm, redstone system, hopper chain,
     * sorting system, or has been custom-named / marked by the player with a sign.
     * These containers must NEVER be touched, emptied, or reorganized by Jarvis.
     */
    public static boolean isAutomationOrFarmContainer(ServerLevel level, BlockPos pos, BlockEntity be) {
        if (level == null || pos == null) return false;

        // Check primary position
        if (checkSinglePosIsAutomationOrFarm(level, pos, be)) {
            return true;
        }

        // If double chest, check the other half as well
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            Direction connected = ChestBlock.getConnectedDirection(state);
            if (connected != null) {
                BlockPos otherPos = pos.relative(connected);
                BlockEntity otherBe = level.getBlockEntity(otherPos);
                if (checkSinglePosIsAutomationOrFarm(level, otherPos, otherBe)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean checkSinglePosIsAutomationOrFarm(ServerLevel level, BlockPos pos, BlockEntity be) {
        // 1. Check custom container name (anvil renamed or custom tagged)
        if (be instanceof Nameable nameable && nameable.hasCustomName()) {
            Component comp = nameable.getCustomName();
            if (comp != null) {
                String name = comp.getString().trim().toLowerCase();
                // If it contains farm/automation keywords, or has any player-given custom name
                // (unless it explicitly mentions jarvis/depo/storage)
                if (name.contains("farm") || name.contains("xp") || name.contains("mob") || name.contains("oto")
                        || name.contains("auto") || name.contains("spawner") || name.contains("grinder")
                        || name.contains("smelt") || name.contains("fırın") || name.contains("loot")
                        || name.contains("trap") || name.contains("filtre") || name.contains("filter")
                        || name.contains("düşen") || name.contains("drop") || name.contains("özel")
                        || name.contains("private") || name.contains("mine") || name.contains("ore")) {
                    return true;
                }
                if (!name.contains("jarvis") && !name.contains("depo") && !name.contains("storage") && !name.startsWith("[")) {
                    return true;
                }
            }
        }

        // 2. Hopper directly underneath (pulling items from chest)
        BlockState belowState = level.getBlockState(pos.below());
        if (belowState.is(Blocks.HOPPER)) {
            return true;
        }

        // 3. Hopper directly above (feeding items into chest)
        BlockState aboveState = level.getBlockState(pos.above());
        if (aboveState.is(Blocks.HOPPER)) {
            return true;
        }

        // 4. Horizontal directions: Hoppers pointing into chest, droppers, dispensers, comparators, and modded pipes/conduits
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adjPos = pos.relative(dir);
            BlockState adjState = level.getBlockState(adjPos);

            // Hopper pointing INTO the chest
            if (adjState.is(Blocks.HOPPER)) {
                if (adjState.hasProperty(HopperBlock.FACING) && adjState.getValue(HopperBlock.FACING) == dir.getOpposite()) {
                    return true;
                }
            }

            // Dropper, Dispenser, or Crafter facing INTO the chest
            if (adjState.is(Blocks.DROPPER) || adjState.is(Blocks.DISPENSER) || adjState.is(Blocks.CRAFTER)) {
                if (adjState.hasProperty(DispenserBlock.FACING) && adjState.getValue(DispenserBlock.FACING) == dir.getOpposite()) {
                    return true;
                }
            }

            // Comparator reading from the chest (facing away from chest)
            if (adjState.is(Blocks.COMPARATOR)) {
                if (adjState.hasProperty(ComparatorBlock.FACING) && adjState.getValue(ComparatorBlock.FACING) == dir) {
                    return true;
                }
            }

            // Modded pipes, conduits, cables, chutes, funnels, buses (Pipez, LaserIO, Mekanism, AE2, RS, Create, etc.)
            String blockId = BuiltInRegistries.BLOCK.getKey(adjState.getBlock()).toString().toLowerCase();
            if (blockId.contains("pipe") || blockId.contains("conduit") || blockId.contains("cable")
                    || blockId.contains("chute") || blockId.contains("funnel") || blockId.contains("transporter")
                    || blockId.contains("storage_bus") || blockId.contains("import_bus") || blockId.contains("export_bus")
                    || blockId.contains("laser_node") || blockId.contains("laser") || blockId.contains("interface")) {
                return true;
            }
        }

        // Also check below and above for modded pipes / conduits / chutes
        String belowId = BuiltInRegistries.BLOCK.getKey(belowState.getBlock()).toString().toLowerCase();
        if (belowId.contains("pipe") || belowId.contains("conduit") || belowId.contains("cable")
                || belowId.contains("chute") || belowId.contains("funnel") || belowId.contains("transporter")
                || belowId.contains("laser_node") || belowId.contains("storage_bus") || belowId.contains("import_bus")) {
            return true;
        }
        String aboveId = BuiltInRegistries.BLOCK.getKey(aboveState.getBlock()).toString().toLowerCase();
        if (aboveId.contains("pipe") || aboveId.contains("conduit") || aboveId.contains("cable")
                || aboveId.contains("chute") || aboveId.contains("funnel") || aboveId.contains("transporter")
                || aboveId.contains("laser_node") || railId(aboveId) || aboveId.contains("storage_bus") || aboveId.contains("export_bus")) {
            return true;
        }

        // 5. Check adjacent signs (wall signs, standing signs, hanging signs)
        BlockPos[] signCheckPositions = new BlockPos[] {
            pos.above(), pos.north(), pos.south(), pos.east(), pos.west(),
            pos.above().north(), pos.above().south(), pos.above().east(), pos.above().west()
        };
        for (BlockPos sPos : signCheckPositions) {
            BlockEntity sBe = level.getBlockEntity(sPos);
            if (sBe instanceof SignBlockEntity signBe) {
                if (hasFarmOrAutomationKeyword(signBe.getText(true)) || hasFarmOrAutomationKeyword(signBe.getText(false))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean railId(String id) {
        return id.contains("hopper_minecart") || id.contains("detector_rail");
    }

    public static ItemCategory getSignCategory(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        List<BlockPos> chestPositions = new ArrayList<>();
        chestPositions.add(pos);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            Direction connected = ChestBlock.getConnectedDirection(state);
            if (connected != null) {
                chestPositions.add(pos.relative(connected));
            }
        }

        for (BlockPos chestPos : chestPositions) {
            BlockPos[] checkPositions = new BlockPos[] {
                chestPos.above(), chestPos.north(), chestPos.south(), chestPos.east(), chestPos.west(),
                chestPos.above().north(), chestPos.above().south(), chestPos.above().east(), chestPos.above().west(),
                chestPos.below()
            };
            for (BlockPos sPos : checkPositions) {
                BlockEntity sBe = level.getBlockEntity(sPos);
                if (sBe instanceof SignBlockEntity signBe) {
                    ItemCategory cat = checkSignTextForCategory(signBe.getText(true));
                    if (cat != null) return cat;
                    cat = checkSignTextForCategory(signBe.getText(false));
                    if (cat != null) return cat;
                }
            }
        }
        return null;
    }

    public static ItemCategory checkSignTextForCategory(SignText text) {
        if (text == null) return null;
        for (int i = 0; i < 4; i++) {
            Component msg = text.getMessage(i, false);
            if (msg == null) continue;
            String str = msg.getString().trim().toLowerCase();
            if (str.isEmpty()) continue;
            str = str.replace("[", "").replace("]", "").replace("\"", "").replace("'", "").trim();
            if (str.contains("ore") || str.contains("maden") || str.contains("mineral")) {
                return ItemCategory.ORES_MINERALS;
            }
            if (str.contains("block") || str.contains("blok") || str.contains("build")) {
                return ItemCategory.BUILDING_BLOCKS;
            }
            if (str.contains("equip") || str.contains("tool") || str.contains("weapon")
                    || str.contains("armor") || str.contains("ekipman") || str.contains("alet") || str.contains("silah")) {
                return ItemCategory.TOOLS_WEAPONS_ARMOR;
            }
            if (str.contains("crop") || str.contains("farm") || str.contains("seed") || str.contains("tarim") || str.contains("tarım") || str.contains("ekin")) {
                return ItemCategory.FARMING;
            }
            if (str.contains("food") || str.contains("yemek") || str.contains("yiyecek")) {
                return ItemCategory.FOOD;
            }
            if (str.contains("magic") || str.contains("buyu") || str.contains("büyü") || str.contains("alchemy") || str.contains("simya")) {
                return ItemCategory.MAGIC_ALCHEMY;
            }
            if (str.contains("tech") || str.contains("machine") || str.contains("makine") || str.contains("mekanism")) {
                return ItemCategory.TECH_MACHINERY;
            }
            if (str.contains("loot") || str.contains("material") || str.contains("malzeme") || str.contains("mob")) {
                return ItemCategory.MATERIALS_MISC;
            }
            if (str.contains("misc") || str.contains("diger") || str.contains("diğer")) {
                return ItemCategory.MISCELLANEOUS;
            }
        }
        return null;
    }

    private static boolean hasFarmOrAutomationKeyword(SignText text) {
        if (text == null) return false;
        // If this sign is an explicit storage category sign (e.g. Ores, Crops, Loot), it is NOT blacklisted!
        if (checkSignTextForCategory(text) != null) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            Component msg = text.getMessage(i, false);
            if (msg == null) continue;
            String str = msg.getString().trim().toLowerCase();
            if (str.isEmpty()) continue;
            if (str.contains("xp") || str.contains("mob") || str.contains("oto")
                    || str.contains("auto") || str.contains("spawner") || str.contains("grinder")
                    || str.contains("smelt") || str.contains("fırın")
                    || str.contains("trap") || str.contains("filtre") || str.contains("filter")
                    || str.contains("özel") || str.contains("private") || str.contains("dokunma")
                    || str.contains("yasak") || str.contains("don't touch") || str.contains("keep")
                    || str.contains("sakla")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isStorageContainer(BlockState state, BlockEntity be) {
        if (state == null) return false;
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase();

        // Exclude smelting / processing machines / furnaces
        if (blockId.contains("furnace") || blockId.contains("smoker") || blockId.contains("blast")
                || blockId.contains("brewing") || blockId.contains("generator") || blockId.contains("crusher")
                || blockId.contains("enrich") || blockId.contains("press") || blockId.contains("assembler")
                || blockId.contains("crafter") || blockId.contains("refinery") || blockId.contains("reactor")
                || blockId.contains("infuser") || blockId.contains("combustion") || blockId.contains("purifier")
                || blockId.contains("chamber") || blockId.contains("smeltery") || blockId.contains("sawmill")
                || blockId.contains("quarry") || blockId.contains("pump") || blockId.contains("miner")) {
            return false;
        }

        // Standard vanilla storage containers
        if (state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            return true;
        }

        // Modded storage blocks
        if (blockId.contains("chest") || blockId.contains("barrel") || blockId.contains("shulker")
                || blockId.contains("drawer") || blockId.contains("storage_box") || blockId.contains("crate")
                || blockId.contains("cabinet") || blockId.contains("safe")) {
            return true;
        }

        return false;
    }

    public static final int MAX_CHEST_STACK_HEIGHT = 4;

    public static int getChestColumnHeight(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return 1;
        BlockPos bottom = pos;
        while (level.getBlockState(bottom.below()).getBlock() instanceof ChestBlock) {
            bottom = bottom.below();
        }
        BlockPos top = pos;
        while (level.getBlockState(top.above()).getBlock() instanceof ChestBlock) {
            top = top.above();
        }
        return top.getY() - bottom.getY() + 1;
    }

    public static BlockPos findIndoorFloorSpot(ServerLevel level, BlockPos center, int baseY, Set<BlockPos> occupied) {
        if (level == null || center == null) return null;

        for (int r = 1; r <= 20; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    for (int dy : new int[]{0, 1, -1}) {
                        BlockPos candidate = new BlockPos(center.getX() + dx, baseY + dy, center.getZ() + dz);

                        if (occupied != null && occupied.contains(candidate)) continue;
                        if (!level.hasChunkAt(candidate)) continue;

                        // Must be indoors under a roof!
                        if (!isUnderRoof(level, candidate)) continue;

                        BlockState state = level.getBlockState(candidate);
                        if (!state.canBeReplaced()) continue;

                        BlockPos below = candidate.below();
                        BlockState belowState = level.getBlockState(below);
                        if (!belowState.isSolid() || belowState.getBlock() instanceof ChestBlock) continue;

                        BlockPos above = candidate.above();
                        BlockState aboveState = level.getBlockState(above);
                        if (!aboveState.canBeReplaced() && !(aboveState.getBlock() instanceof ChestBlock)) continue;

                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static BlockPos placeFloorDoubleChest(ServerLevel level, BlockPos floorPos, Direction facing, String label) {
        if (level == null || floorPos == null) return null;
        if (!level.getBlockState(floorPos).canBeReplaced()) return null;

        Direction perp = facing.getClockWise();
        BlockPos otherTarget = floorPos.relative(perp);
        ChestType type1 = ChestType.LEFT;
        ChestType type2 = ChestType.RIGHT;

        boolean canPlaceOther = level.getBlockState(otherTarget).canBeReplaced()
                && level.getBlockState(otherTarget.below()).isSolid()
                && isUnderRoof(level, otherTarget);

        if (!canPlaceOther) {
            perp = facing.getCounterClockWise();
            otherTarget = floorPos.relative(perp);
            type1 = ChestType.RIGHT;
            type2 = ChestType.LEFT;
            canPlaceOther = level.getBlockState(otherTarget).canBeReplaced()
                    && level.getBlockState(otherTarget.below()).isSolid()
                    && isUnderRoof(level, otherTarget);
        }

        if (canPlaceOther) {
            BlockState s1 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type1);
            BlockState s2 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type2);
            level.setBlock(floorPos, s1, 3);
            level.setBlock(otherTarget, s2, 3);
            setContainerCustomName(level, floorPos, label);
            setContainerCustomName(level, otherTarget, label);
            level.playSound(null, floorPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return floorPos;
        } else {
            BlockState s1 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.SINGLE);
            level.setBlock(floorPos, s1, 3);
            setContainerCustomName(level, floorPos, label);
            level.playSound(null, floorPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return floorPos;
        }
    }

    public static boolean matchesFilter(ItemStack stack, String filter) {
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            return true;
        }
        String f = filter.trim().toLowerCase();
        ItemCategory category = ItemCategory.classify(stack);
        if (category.matchesFilter(f)) {
            return true;
        }
        return matchesItemId(stack, f);
    }

    public static boolean matchesItemId(ItemStack stack, String query) {
        if (stack == null || stack.isEmpty() || query == null || query.isBlank()) {
            return false;
        }
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String fullId = loc.toString().toLowerCase();
        String path = loc.getPath().toLowerCase();
        String q = query.trim().toLowerCase();
        String qClean = q.replace(' ', '_').replace('-', '_');

        return fullId.equals(q) || path.equals(q) || fullId.equals(qClean) || path.equals(qClean)
                || (q.startsWith("minecraft:") && fullId.equals(q))
                || ("minecraft:" + path).equals(q)
                || ("minecraft:" + path).equals(qClean);
    }

    public static void setContainerCustomName(ServerLevel level, BlockPos pos, String customName) {
        if (level == null || pos == null || customName == null || customName.isBlank()) return;
        if (getSignCategory(level, pos) != null) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            try {
                for (java.lang.reflect.Method m : be.getClass().getMethods()) {
                    if (m.getName().equals("setCustomName") && m.getParameterCount() == 1) {
                        m.invoke(be, Component.literal(customName));
                        be.setChanged();
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Places a new double chest stacked directly on top of the given container.
     * Enforces MAX_CHEST_STACK_HEIGHT (at most 4 chests high per column) and prevents punching roofs.
     */
    public static BlockPos placeStackedDoubleChest(ServerLevel level, BlockPos basePos, String label) {
        if (level == null || basePos == null) return null;

        // Strict height enforcement: column must not exceed 4 chests
        if (getChestColumnHeight(level, basePos) >= MAX_CHEST_STACK_HEIGHT) {
            return null;
        }

        BlockPos targetPos = basePos.above();
        if (!level.getBlockState(targetPos).canBeReplaced()) {
            return null;
        }

        // Must stay indoors if base was indoors
        if (isUnderRoof(level, basePos) && !isUnderRoof(level, targetPos)) {
            return null;
        }

        BlockState baseState = level.getBlockState(basePos);
        Direction facing = Direction.NORTH;
        BlockPos otherTarget = null;
        ChestType type1 = ChestType.SINGLE;
        ChestType type2 = ChestType.SINGLE;

        if (baseState.getBlock() instanceof ChestBlock) {
            facing = baseState.getValue(ChestBlock.FACING);
            Direction connected = ChestBlock.getConnectedDirection(baseState);
            if (connected != null) {
                BlockPos otherBase = basePos.relative(connected);
                otherTarget = new BlockPos(otherBase.getX(), targetPos.getY(), otherBase.getZ());
                ChestType baseType = baseState.getValue(ChestBlock.TYPE);
                type1 = baseType;
                type2 = (baseType == ChestType.LEFT) ? ChestType.RIGHT : ChestType.LEFT;
            }
        }

        if (otherTarget == null || !level.getBlockState(otherTarget).canBeReplaced()) {
            Direction perp = facing.getClockWise();
            BlockPos candidate = targetPos.relative(perp);
            if (level.getBlockState(candidate).canBeReplaced() && (!isUnderRoof(level, basePos) || isUnderRoof(level, candidate))) {
                otherTarget = candidate;
                type1 = ChestType.LEFT;
                type2 = ChestType.RIGHT;
            } else {
                perp = facing.getCounterClockWise();
                candidate = targetPos.relative(perp);
                if (level.getBlockState(candidate).canBeReplaced() && (!isUnderRoof(level, basePos) || isUnderRoof(level, candidate))) {
                    otherTarget = candidate;
                    type1 = ChestType.RIGHT;
                    type2 = ChestType.LEFT;
                }
            }
        }

        if (otherTarget != null && level.getBlockState(otherTarget).canBeReplaced()) {
            BlockState s1 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type1);
            BlockState s2 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type2);
            level.setBlock(targetPos, s1, 3);
            level.setBlock(otherTarget, s2, 3);
            setContainerCustomName(level, targetPos, label);
            setContainerCustomName(level, otherTarget, label);
            level.playSound(null, targetPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return targetPos;
        } else {
            BlockState s1 = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.SINGLE);
            level.setBlock(targetPos, s1, 3);
            setContainerCustomName(level, targetPos, label);
            level.playSound(null, targetPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return targetPos;
        }
    }

    private static boolean isEquipped(JarvisCompanionEntity companion, ItemStack stack) {
        if (companion == null || stack == null || stack.isEmpty()) return false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (companion.getItemBySlot(slot) == stack) return true;
        }
        return false;
    }

    /**
     * Categorizes and organizes all base containers into dedicated category chests:
     * Ores, Food, Building, Equipment, Farming, and Materials.
     * When any category chest fills up, auto-stacks a new double chest directly on top!
     */
    public static JsonObject organizeBaseStorage(ServerLevel level, BlockPos center, int rawRadius) {
        JsonObject result = new JsonObject();
        int radius = Math.max(8, Math.min(rawRadius, 48));

        JsonObject centerObj = new JsonObject();
        centerObj.addProperty("x", center.getX());
        centerObj.addProperty("y", center.getY());
        centerObj.addProperty("z", center.getZ());
        result.add("center", centerObj);
        result.addProperty("radius", radius);

        List<ContainerCandidate> candidates = findContainers(level, center, radius);
        if (candidates.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "No containers found within " + radius + " blocks");
            return result;
        }

        Set<BlockPos> uniquePositions = new HashSet<>();
        List<BlockPos> containerPositions = new ArrayList<>();
        for (ContainerCandidate c : candidates) {
            BlockPos p = c.pos;
            BlockState bs = level.getBlockState(p);
            if (bs.getBlock() instanceof ChestBlock) {
                Direction connected = ChestBlock.getConnectedDirection(bs);
                if (connected != null) {
                    BlockPos other = p.relative(connected);
                    if (uniquePositions.contains(other)) {
                        continue;
                    }
                }
            }
            uniquePositions.add(p);
            containerPositions.add(p);
        }

        List<ItemStack> allItems = new ArrayList<>();
        for (BlockPos p : containerPositions) {
            IItemHandler h = getItemHandler(level, p);
            if (h == null) continue;
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack stack = h.getStackInSlot(s);
                if (!stack.isEmpty()) {
                    allItems.add(stack.copy());
                }
            }
            if (h instanceof IItemHandlerModifiable modifiable) {
                for (int s = 0; s < modifiable.getSlots(); s++) {
                    modifiable.setStackInSlot(s, ItemStack.EMPTY);
                }
                markDirty(level, p);
            } else {
                for (int s = 0; s < h.getSlots(); s++) {
                    h.extractItem(s, 64, false);
                }
                markDirty(level, p);
            }
        }

        JarvisCompanionEntity companion = com.alcyone.jarvis.entity.CompanionManager.getInstance().getActiveCompanion();
        if (companion != null && companion.isAlive() && companion.level() == level) {
            if (companion.blockPosition().closerThan(center, radius + 8)) {
                ItemStackHandler compInv = companion.getInventory();
                for (int s = 0; s < compInv.getSlots(); s++) {
                    ItemStack stack = compInv.getStackInSlot(s);
                    if (!stack.isEmpty() && !isEquipped(companion, stack)) {
                        allItems.add(stack.copy());
                        compInv.setStackInSlot(s, ItemStack.EMPTY);
                    }
                }
            }
        }

        List<ItemStack> compactedItems = new ArrayList<>();
        for (ItemStack item : allItems) {
            boolean merged = false;
            for (ItemStack existing : compactedItems) {
                if (ItemStack.isSameItemSameComponents(item, existing)) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    if (space > 0) {
                        int toAdd = Math.min(space, item.getCount());
                        existing.grow(toAdd);
                        item.shrink(toAdd);
                        if (item.isEmpty()) {
                            merged = true;
                            break;
                        }
                    }
                }
            }
            if (!item.isEmpty()) {
                compactedItems.add(item);
            }
        }

        Map<ItemCategory, List<ItemStack>> categoryItems = new EnumMap<>(ItemCategory.class);
        for (ItemCategory cat : ItemCategory.values()) {
            categoryItems.put(cat, new ArrayList<>());
        }
        for (ItemStack stack : compactedItems) {
            ItemCategory cat = ItemCategory.classify(stack);
            categoryItems.get(cat).add(stack);
        }

        for (List<ItemStack> list : categoryItems.values()) {
            list.sort((a, b) -> {
                String na = BuiltInRegistries.ITEM.getKey(a.getItem()).toString();
                String nb = BuiltInRegistries.ITEM.getKey(b.getItem()).toString();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
                return Integer.compare(b.getCount(), a.getCount());
            });
        }

        Map<ItemCategory, List<BlockPos>> categoryChests = new EnumMap<>(ItemCategory.class);
        for (ItemCategory cat : ItemCategory.values()) {
            categoryChests.put(cat, new ArrayList<>());
        }

        // Identify indoor storage center and lowest floor level from existing indoor chests
        int sumX = 0, sumY = 0, sumZ = 0, indoorCount = 0;
        int lowestFloorY = Integer.MAX_VALUE;

        for (ContainerCandidate c : candidates) {
            if (isUnderRoof(level, c.pos)) {
                sumX += c.pos.getX();
                sumY += c.pos.getY();
                sumZ += c.pos.getZ();
                indoorCount++;
                lowestFloorY = Math.min(lowestFloorY, c.pos.getY());
            }
        }

        final BlockPos indoorCenter;
        final int floorY;
        if (indoorCount > 0) {
            indoorCenter = new BlockPos(sumX / indoorCount, lowestFloorY, sumZ / indoorCount);
            floorY = lowestFloorY;
        } else {
            indoorCenter = center;
            floorY = center.getY();
        }

        List<BlockPos> availableChests = new ArrayList<>();
        for (BlockPos p : containerPositions) {
            if (isUnderRoof(level, p)) {
                availableChests.add(p);
            }
        }
        if (availableChests.isEmpty()) {
            availableChests.addAll(containerPositions);
        }
        availableChests.sort((a, b) -> Double.compare(a.distSqr(indoorCenter), b.distSqr(indoorCenter)));

        Set<BlockPos> occupiedPositions = new HashSet<>(containerPositions);

        Map<ItemCategory, String> categoryLabels = new EnumMap<>(ItemCategory.class);
        categoryLabels.put(ItemCategory.ORES_MINERALS, "Maden Deposu");
        categoryLabels.put(ItemCategory.FOOD, "Yemek Deposu");
        categoryLabels.put(ItemCategory.BUILDING_BLOCKS, "Blok Deposu");
        categoryLabels.put(ItemCategory.TOOLS_WEAPONS_ARMOR, "Ekipman Deposu");
        categoryLabels.put(ItemCategory.FARMING, "Tarim Deposu");
        categoryLabels.put(ItemCategory.MATERIALS_MISC, "Malzeme Deposu");
        categoryLabels.put(ItemCategory.TECH_MACHINERY, "Teknoloji Deposu");
        categoryLabels.put(ItemCategory.MAGIC_ALCHEMY, "Buyu Deposu");
        categoryLabels.put(ItemCategory.MISCELLANEOUS, "Genel Depo");

        ItemCategory[] priorityCats = {
            ItemCategory.ORES_MINERALS,
            ItemCategory.FOOD,
            ItemCategory.BUILDING_BLOCKS,
            ItemCategory.TOOLS_WEAPONS_ARMOR,
            ItemCategory.FARMING,
            ItemCategory.MATERIALS_MISC,
            ItemCategory.TECH_MACHINERY,
            ItemCategory.MAGIC_ALCHEMY,
            ItemCategory.MISCELLANEOUS
        };

        // 1. Map chests that have category signs
        Map<ItemCategory, List<BlockPos>> signedChests = new EnumMap<>(ItemCategory.class);
        for (ItemCategory cat : ItemCategory.values()) {
            signedChests.put(cat, new ArrayList<>());
        }
        List<BlockPos> unassignedChests = new ArrayList<>();

        for (BlockPos p : availableChests) {
            ItemCategory signCat = getSignCategory(level, p);
            if (signCat != null) {
                signedChests.get(signCat).add(p);
            } else {
                unassignedChests.add(p);
            }
        }

        // 2. Pre-assign signed chests to their dedicated categories
        for (ItemCategory cat : ItemCategory.values()) {
            categoryChests.get(cat).addAll(signedChests.get(cat));
        }

        // 3. For categories with items but no signed chests, assign from unassignedChests
        for (ItemCategory cat : priorityCats) {
            List<ItemStack> items = categoryItems.get(cat);
            if (items == null || items.isEmpty()) continue;

            if (categoryChests.get(cat).isEmpty() && !unassignedChests.isEmpty()) {
                BlockPos primaryPos = unassignedChests.remove(0);
                categoryChests.get(cat).add(primaryPos);
                setContainerCustomName(level, primaryPos, categoryLabels.get(cat));
            }
        }

        int newChestsPlaced = 0;
        int totalItemsOrganized = 0;
        JsonObject categorySummary = new JsonObject();

        for (ItemCategory cat : priorityCats) {
            List<ItemStack> items = categoryItems.get(cat);
            if (items == null || items.isEmpty()) continue;

            List<BlockPos> chestList = categoryChests.get(cat);
            if (chestList.isEmpty()) continue;

            int catItemCount = 0;
            int chestIdx = 0;
            BlockPos currentChest = chestList.get(0);

            for (ItemStack stack : items) {
                while (!stack.isEmpty()) {
                    IItemHandler handler = getItemHandler(level, currentChest);
                    if (handler == null) break;

                    ItemStack remainder = ItemHandlerHelper.insertItem(handler, stack.copy(), false);
                    int inserted = stack.getCount() - remainder.getCount();
                    catItemCount += inserted;
                    totalItemsOrganized += inserted;
                    stack = remainder;

                    if (!stack.isEmpty()) {
                        chestIdx++;
                        if (chestIdx < chestList.size()) {
                            currentChest = chestList.get(chestIdx);
                        } else if (!unassignedChests.isEmpty()) {
                            currentChest = unassignedChests.remove(0);
                            chestList.add(currentChest);
                            setContainerCustomName(level, currentChest, categoryLabels.get(cat));
                        } else {
                            if (companion != null) {
                                companion.spawnAtLocation(stack.copy());
                            }
                            stack = ItemStack.EMPTY;
                        }
                    }
                }
            }

            for (BlockPos cp : chestList) {
                markDirty(level, cp);
            }

            JsonObject catObj = new JsonObject();
            catObj.addProperty("items", catItemCount);
            JsonArray posArr = new JsonArray();
            for (BlockPos cp : chestList) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("x", cp.getX());
                pObj.addProperty("y", cp.getY());
                pObj.addProperty("z", cp.getZ());
                posArr.add(pObj);
            }
            catObj.add("chests", posArr);
            categorySummary.add(cat.getDisplayName(), catObj);
        }

        result.addProperty("success", true);
        result.addProperty("action", "organize_storage");
        result.addProperty("total_items_organized", totalItemsOrganized);
        result.addProperty("containers_scanned", containerPositions.size());
        result.addProperty("new_chests_placed", newChestsPlaced);
        result.add("categories", categorySummary);
        result.addProperty("message", "Madenler, yemekler, bloklar, ekipmanlar, tarim ve malzemeler ayri sandiklara toplandi. Dolan sandiklarin uzerine " + newChestsPlaced + " adet yeni kat yerlestirildi Efendim.");

        return result;
    }
}
