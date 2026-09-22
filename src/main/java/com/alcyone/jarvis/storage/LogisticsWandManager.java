package com.alcyone.jarvis.storage;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.gui.JarvisWandMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.*;

/**
 * High-performance Logistics Wand for Jarvis.
 * Allows players to select a source chest with Shift+Right-Click and bulk-transfer
 * matching items directly into the targeted container with normal Right-Click,
 * as well as pulling from inventory or nearby unsorted chests.
 */
public class LogisticsWandManager {
    public static final String WAND_TAG = "jarvis_wand";
    public static final String FILTER_TAG = "FilterItems";
    public static final String SOURCE_POS_TAG = "SourcePos";
    public static final String SOURCE_DIM_TAG = "SourceDim";

    public static final Map<UUID, BlockPos> PLAYER_SOURCE_CHEST = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<UUID, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> PLAYER_SOURCE_DIM = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && customData.contains(WAND_TAG)) {
            return true;
        }
        String name = stack.getHoverName().getString().toLowerCase();
        return (stack.is(Items.BLAZE_ROD) || stack.is(Items.STICK) || stack.is(Items.NETHER_STAR))
                && (name.contains("jarvis") || name.contains("wand") || name.contains("asa") || name.contains("lojistik"));
    }

    public static ItemStack createWand() {
        ItemStack wand = new ItemStack(Items.BLAZE_ROD);
        wand.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lJarvis Lojistik Asası"));
        wand.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(WAND_TAG, true);
        tag.put(FILTER_TAG, new ListTag());
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        updateWandLore(wand, Collections.emptyList());
        return wand;
    }

    public static List<String> getFilterItemIds(ItemStack wand) {
        List<String> list = new ArrayList<>();
        if (wand == null || wand.isEmpty()) return list;
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            if (tag.contains(FILTER_TAG, Tag.TAG_LIST)) {
                ListTag listTag = tag.getList(FILTER_TAG, Tag.TAG_STRING);
                for (int i = 0; i < listTag.size(); i++) {
                    list.add(listTag.getString(i));
                }
            }
        }
        return list;
    }

    public static void saveFilterItemIds(ItemStack wand, List<String> ids) {
        if (wand == null || wand.isEmpty()) return;
        CustomData customData = wand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        tag.putBoolean(WAND_TAG, true);

        ListTag listTag = new ListTag();
        for (String id : ids) {
            listTag.add(StringTag.valueOf(id));
        }
        tag.put(FILTER_TAG, listTag);
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        updateWandLore(wand, ids);
    }

    public static BlockPos getSourcePos(ItemStack wand, ServerLevel level) {
        return getSourcePos(wand, level, null);
    }

    public static BlockPos getSourcePos(ItemStack wand, ServerLevel level, ServerPlayer player) {
        if (player != null) {
            BlockPos memPos = PLAYER_SOURCE_CHEST.get(player.getUUID());
            if (memPos != null) {
                if (level != null) {
                    var dim = PLAYER_SOURCE_DIM.get(player.getUUID());
                    if (dim == null || dim.equals(level.dimension())) {
                        return memPos;
                    }
                } else {
                    return memPos;
                }
            }
        }
        if (wand == null || wand.isEmpty()) return null;
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            if (tag.contains(SOURCE_POS_TAG)) {
                if (level != null && tag.contains(SOURCE_DIM_TAG)) {
                    String dim = tag.getString(SOURCE_DIM_TAG);
                    if (!dim.isEmpty() && !dim.equals(level.dimension().location().toString())) {
                        return null; // Different dimension
                    }
                }
                return BlockPos.of(tag.getLong(SOURCE_POS_TAG));
            }
        }
        return null;
    }

    public static void setSourcePos(ItemStack wand, BlockPos pos, ServerLevel level) {
        setSourcePos(wand, pos, level, null);
    }

    public static void setSourcePos(ItemStack wand, BlockPos pos, ServerLevel level, ServerPlayer player) {
        if (player != null && pos != null) {
            PLAYER_SOURCE_CHEST.put(player.getUUID(), pos);
            if (level != null) {
                PLAYER_SOURCE_DIM.put(player.getUUID(), level.dimension());
            }
        }
        if (wand == null || wand.isEmpty() || pos == null) return;
        CustomData customData = wand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        tag.putBoolean(WAND_TAG, true);
        tag.putLong(SOURCE_POS_TAG, pos.asLong());
        if (level != null) {
            tag.putString(SOURCE_DIM_TAG, level.dimension().location().toString());
        }
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        updateWandLore(wand, getFilterItemIds(wand), pos);
    }

    public static void clearSourcePos(ItemStack wand, ServerPlayer player) {
        if (player != null) {
            PLAYER_SOURCE_CHEST.remove(player.getUUID());
            PLAYER_SOURCE_DIM.remove(player.getUUID());
        }
        if (wand == null || wand.isEmpty()) return;
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            boolean hadSource = tag.contains(SOURCE_POS_TAG);
            tag.remove(SOURCE_POS_TAG);
            tag.remove(SOURCE_DIM_TAG);
            wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            updateWandLore(wand, getFilterItemIds(wand), null);

            if (player != null && hadSource) {
                player.displayClientMessage(Component.literal("§e[Jarvis Asası] Kaynak sandık seçimi sıfırlandı."), true);
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.8f, 0.9f);
            }
        }
    }

    public static void updateWandLore(ItemStack wand, List<String> filterIds) {
        BlockPos sourcePos = getSourcePos(wand, null);
        updateWandLore(wand, filterIds, sourcePos);
    }

    public static void updateWandLore(ItemStack wand, List<String> filterIds, BlockPos sourcePos) {
        List<Component> lore = new ArrayList<>();
        if (sourcePos != null) {
            lore.add(Component.literal("§bKaynak Sandık: §f(" + sourcePos.getX() + ", " + sourcePos.getY() + ", " + sourcePos.getZ() + ")"));
        } else {
            lore.add(Component.literal("§7Kaynak Sandık: §8(Seçilmedi)"));
        }
        lore.add(Component.literal("§7Filtredeki Eşyalar:"));
        if (filterIds.isEmpty()) {
            lore.add(Component.literal("  §8(Filtre boş)"));
        } else {
            int maxShow = Math.min(filterIds.size(), 6);
            for (int i = 0; i < maxShow; i++) {
                String id = filterIds.get(i);
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
                String name = (item != null && item != Items.AIR) ? item.getDescription().getString() : id;
                lore.add(Component.literal("  §e• " + name));
            }
            if (filterIds.size() > 6) {
                lore.add(Component.literal("  §8... ve " + (filterIds.size() - 6) + " eşya daha"));
            }
        }
        lore.add(Component.literal("§8§m------------------------"));
        lore.add(Component.literal("§aSağ Tık (Sandığa): §7Kaynak sandıktan bu sandığa aktar"));
        lore.add(Component.literal("§bShift + Sağ Tık (Sandığa): §7Kaynak sandık & filtre olarak seç"));
        lore.add(Component.literal("§eSağ Tık (Havaya): §7Filtre arayüzünü aç"));
        lore.add(Component.literal("§cShift + Sol Tık: §7Kaynak sandık seçimini sıfırla"));

        wand.set(DataComponents.LORE, new ItemLore(lore));
    }

    public static void openWandFilterMenu(ServerPlayer player, ItemStack wand) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, p) -> new JarvisWandMenu(containerId, playerInventory, wand, player),
                Component.literal("§6§lJarvis Asası §8- §eEşya Filtresi (100 Slot)")
        ));
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    public static void handleChestClick(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, ItemStack wand, boolean isShift) {
        IItemHandler targetHandler = StorageManager.getItemHandler(level, pos);
        if (targetHandler == null) return;

        if (isShift) {
            // MODE: CLONE CHEST ITEMS INTO WAND FILTER & MARK AS SOURCE CONTAINER
            Set<String> foundIds = new LinkedHashSet<>();
            for (int s = 0; s < targetHandler.getSlots(); s++) {
                ItemStack stack = targetHandler.getStackInSlot(s);
                if (!stack.isEmpty()) {
                    foundIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    if (foundIds.size() >= 100) break;
                }
            }

            if (foundIds.isEmpty()) {
                clearSourcePos(wand, player);
                player.displayClientMessage(Component.literal("§c[Jarvis Asası] Bu sandık boş! Kaynak sandık seçimi sıfırlandı."), true);
                player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f);
                return;
            }

            List<String> idList = new ArrayList<>(foundIds);
            saveFilterItemIds(wand, idList);
            setSourcePos(wand, pos, level, player);

            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.1);
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.2f);
            player.displayClientMessage(Component.literal("§a[Jarvis Asası] §eKaynak sandık seçildi: §f(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") §a| " + idList.size() + " farklı eşya türü filtrelendi!"), true);

        } else {
            // MODE: DIRECT TRANSFER FROM SOURCE CHEST TO TARGET CHEST
            BlockPos sourcePos = getSourcePos(wand, level, player);
            if (sourcePos != null && sourcePos.equals(pos)) {
                player.displayClientMessage(Component.literal("§e[Jarvis Asası] Bu zaten kaynak sandık! Eşyaları aktarmak istediğiniz hedef sandığa sağ tıklayın."), true);
                player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f);
                return;
            }

            List<String> filterIds = getFilterItemIds(wand);
            Set<String> filterSet = new HashSet<>(filterIds);

            if (filterSet.isEmpty() && sourcePos == null) {
                player.displayClientMessage(Component.literal("§e[Jarvis Asası] Filtre veya kaynak sandık seçilmedi! Bir sandığa Shift+Sağ Tık yaparak kaynak sandık belirleyin."), true);
                player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f);
                return;
            }

            int totalTransferred = 0;
            int fromSourceTransferred = 0;
            int fromInventoryTransferred = 0;

            // 1. PRIMARY TRANSFER: Directly from marked Source Chest
            if (sourcePos != null) {
                IItemHandler sourceHandler = StorageManager.getItemHandler(level, sourcePos);
                if (sourceHandler != null) {
                    for (int s = 0; s < sourceHandler.getSlots(); s++) {
                        while (true) {
                            ItemStack sStack = sourceHandler.getStackInSlot(s);
                            if (sStack.isEmpty()) break;
                            String itemId = BuiltInRegistries.ITEM.getKey(sStack.getItem()).toString();
                            if (!filterSet.isEmpty() && !filterSet.contains(itemId)) {
                                break;
                            }
                            ItemStack sim = ItemHandlerHelper.insertItem(targetHandler, sStack, true);
                            int canFit = sStack.getCount() - sim.getCount();
                            if (canFit <= 0) {
                                break; // Target cannot fit any more of this item
                            }
                            ItemStack extracted = sourceHandler.extractItem(s, canFit, false);
                            if (extracted.isEmpty()) {
                                break;
                            }
                            ItemStack leftover = ItemHandlerHelper.insertItem(targetHandler, extracted, false);
                            if (!leftover.isEmpty()) {
                                ItemHandlerHelper.insertItem(sourceHandler, leftover, false);
                            }
                            int moved = extracted.getCount() - leftover.getCount();
                            if (moved <= 0) {
                                break;
                            }
                            fromSourceTransferred += moved;
                            totalTransferred += moved;
                        }
                    }
                    StorageManager.markDirty(level, sourcePos);
                } else {
                    clearSourcePos(wand, player);
                    player.displayClientMessage(Component.literal("§c[Jarvis Asası] Kaynak sandık bulunamadı veya kırılmış!"), true);
                    player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f);
                    return;
                }
            } else {
                // FALLBACK ONLY IF NO SOURCE CHEST WAS MARKED:
                // 2. Pull from Player Inventory
                for (int s = 0; s < player.getInventory().items.size(); s++) {
                    ItemStack pStack = player.getInventory().items.get(s);
                    if (pStack.isEmpty() || pStack == wand) continue;
                    String itemId = BuiltInRegistries.ITEM.getKey(pStack.getItem()).toString();
                    if (filterSet.contains(itemId)) {
                        ItemStack sim = ItemHandlerHelper.insertItem(targetHandler, pStack, true);
                        int canFit = pStack.getCount() - sim.getCount();
                        if (canFit > 0) {
                            ItemStack toInsert = pStack.split(canFit);
                            ItemHandlerHelper.insertItem(targetHandler, toInsert, false);
                            fromInventoryTransferred += canFit;
                            totalTransferred += canFit;
                        }
                    }
                }

                // 3. Companion Inventory (if companion is nearby)
                List<JarvisCompanionEntity> companions = level.getEntitiesOfClass(JarvisCompanionEntity.class, player.getBoundingBox().inflate(32));
                for (JarvisCompanionEntity comp : companions) {
                    ItemStackHandler cInv = comp.getInventory();
                    for (int s = 0; s < cInv.getSlots(); s++) {
                        ItemStack cStack = cInv.getStackInSlot(s);
                        if (cStack.isEmpty()) continue;
                        String itemId = BuiltInRegistries.ITEM.getKey(cStack.getItem()).toString();
                        if (filterSet.contains(itemId)) {
                            ItemStack sim = ItemHandlerHelper.insertItem(targetHandler, cStack, true);
                            int canFit = cStack.getCount() - sim.getCount();
                            if (canFit > 0) {
                                ItemStack extracted = cInv.extractItem(s, canFit, false);
                                if (!extracted.isEmpty()) {
                                    ItemHandlerHelper.insertItem(targetHandler, extracted, false);
                                    totalTransferred += extracted.getCount();
                                }
                            }
                        }
                    }
                }

                // 4. Nearby unsorted containers
                BlockPos otherHalf = null;
                BlockState bState = level.getBlockState(pos);
                if (bState.getBlock() instanceof ChestBlock) {
                    Direction connected = ChestBlock.getConnectedDirection(bState);
                    if (connected != null) {
                        otherHalf = pos.relative(connected);
                    }
                }

                List<StorageManager.ContainerCandidate> candidates = StorageManager.findContainers(level, player.blockPosition(), 32);
                for (StorageManager.ContainerCandidate candidate : candidates) {
                    if (candidate.pos.equals(pos) || (otherHalf != null && candidate.pos.equals(otherHalf))) {
                        continue;
                    }
                    IItemHandler sourceHandler = candidate.handler;
                    if (sourceHandler == null) continue;

                    for (int s = 0; s < sourceHandler.getSlots(); s++) {
                        ItemStack sStack = sourceHandler.getStackInSlot(s);
                        if (sStack.isEmpty()) continue;
                        String itemId = BuiltInRegistries.ITEM.getKey(sStack.getItem()).toString();
                        if (filterSet.contains(itemId)) {
                            ItemStack sim = ItemHandlerHelper.insertItem(targetHandler, sStack, true);
                            int canFit = sStack.getCount() - sim.getCount();
                            if (canFit > 0) {
                                ItemStack extracted = sourceHandler.extractItem(s, canFit, false);
                                if (!extracted.isEmpty()) {
                                    ItemHandlerHelper.insertItem(targetHandler, extracted, false);
                                    totalTransferred += extracted.getCount();
                                    StorageManager.markDirty(level, candidate.pos);
                                }
                            }
                        }
                    }
                }
            }

            StorageManager.markDirty(level, pos);

            if (totalTransferred > 0) {
                // Visual particle beam between source chest and target chest
                if (sourcePos != null && Math.sqrt(sourcePos.distSqr(pos)) <= 48) {
                    double dx = pos.getX() - sourcePos.getX();
                    double dy = pos.getY() - sourcePos.getY();
                    double dz = pos.getZ() - sourcePos.getZ();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int steps = (int) Math.max(5, dist * 2);
                    for (int i = 0; i <= steps; i++) {
                        double t = (double) i / steps;
                        level.sendParticles(ParticleTypes.ENCHANTED_HIT,
                                sourcePos.getX() + 0.5 + dx * t,
                                sourcePos.getY() + 0.6 + dy * t,
                                sourcePos.getZ() + 0.5 + dz * t,
                                1, 0, 0, 0, 0);
                    }
                    level.sendParticles(ParticleTypes.PORTAL, sourcePos.getX() + 0.5, sourcePos.getY() + 0.8, sourcePos.getZ() + 0.5, 12, 0.2, 0.2, 0.2, 0.05);
                }

                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.1);
                level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 1.0f, 1.0f);
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);

                StringBuilder sb = new StringBuilder("§a[Jarvis Asası] §f" + totalTransferred + " §aadet eşya aktarıldı!");
                if (fromSourceTransferred > 0 && fromInventoryTransferred > 0) {
                    sb.append(" §7(Sandıktan: §f").append(fromSourceTransferred).append("§7, Envanterden: §f").append(fromInventoryTransferred).append("§7)");
                } else if (fromSourceTransferred > 0) {
                    sb.append(" §7(Kaynak sandıktan: §f").append(fromSourceTransferred).append("§7)");
                }
                player.displayClientMessage(Component.literal(sb.toString()), true);
            } else {
                if (sourcePos != null) {
                    player.displayClientMessage(Component.literal("§e[Jarvis Asası] Kaynak sandıkta filtrelenen eşyadan kalmadı (veya hedef sandık dolu)."), true);
                } else {
                    player.displayClientMessage(Component.literal("§e[Jarvis Asası] Filtreyle eşleşen aktarılacak eşya bulunamadı."), true);
                }
                player.playNotifySound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.6f, 0.8f);
            }
        }
    }
}
