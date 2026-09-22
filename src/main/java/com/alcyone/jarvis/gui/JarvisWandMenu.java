package com.alcyone.jarvis.gui;

import com.alcyone.jarvis.storage.LogisticsWandManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Interactive 100-slot Ghost Filter Menu for the Jarvis Logistics Wand.
 * Uses a 6-row container (MenuType.GENERIC_9x6) with 3 pages (45 + 45 + 10 = 100 slots).
 * Allows players to set item filters without consuming their actual items.
 */
public class JarvisWandMenu extends ChestMenu {
    public static final int TOTAL_FILTER_SLOTS = 100;
    public static final int SLOTS_PER_PAGE = 45; // 5 rows of 9
    public static final int TOTAL_PAGES = 3;
    public static final int CONTAINER_SIZE = 54; // 6 rows of 9

    public static final int PREV_PAGE_SLOT = 45;
    public static final int CLEAR_FILTER_SLOT = 50;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;

    private final ItemStack wandStack;
    private final SimpleContainer filterContainer;
    private final ServerPlayer serverPlayer;
    private final String[] filterSlots = new String[TOTAL_FILTER_SLOTS];
    private int currentPage = 0;

    public JarvisWandMenu(int containerId, Inventory playerInventory, ItemStack wandStack, ServerPlayer player) {
        this(containerId, playerInventory, new SimpleContainer(CONTAINER_SIZE), wandStack, player);
    }

    private JarvisWandMenu(int containerId, Inventory playerInventory, SimpleContainer filterContainer, ItemStack wandStack, ServerPlayer player) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, filterContainer, 6);
        this.wandStack = wandStack;
        this.filterContainer = filterContainer;
        this.serverPlayer = player;
        loadFilterFromWand();
    }

    private void loadFilterFromWand() {
        Arrays.fill(filterSlots, null);
        List<String> filterIds = LogisticsWandManager.getFilterItemIds(wandStack);
        for (int i = 0; i < Math.min(filterIds.size(), TOTAL_FILTER_SLOTS); i++) {
            filterSlots[i] = filterIds.get(i);
        }
        loadPage();
    }

    private void loadPage() {
        int startIdx = currentPage * SLOTS_PER_PAGE;
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            int globalIndex = startIdx + i;
            if (globalIndex < TOTAL_FILTER_SLOTS) {
                String id = filterSlots[globalIndex];
                if (id != null && !id.isEmpty()) {
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
                    if (item != null && item != Items.AIR) {
                        filterContainer.setItem(i, new ItemStack(item, 1));
                    } else {
                        filterContainer.setItem(i, ItemStack.EMPTY);
                    }
                } else {
                    filterContainer.setItem(i, ItemStack.EMPTY);
                }
            } else {
                // Locked slot (above 100 on page 3)
                ItemStack locked = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                locked.set(DataComponents.CUSTOM_NAME, Component.literal("§8Kilitli Slot"));
                List<Component> lore = List.of(Component.literal("§7(Maksimum 100 filtre slotu)"));
                locked.set(DataComponents.LORE, new ItemLore(lore));
                filterContainer.setItem(i, locked);
            }
        }
        updateToolbar();
        this.broadcastChanges();
    }

    private void updateToolbar() {
        // Decorative / empty panes
        ItemStack decor = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        decor.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int s = 45; s < 54; s++) {
            filterContainer.setItem(s, decor.copy());
        }

        // Previous Page Button (Slot 45)
        if (currentPage > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§a◀ Önceki Sayfa (Sayfa " + currentPage + ")"));
            filterContainer.setItem(PREV_PAGE_SLOT, prev);
        } else {
            ItemStack prevDisabled = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            prevDisabled.set(DataComponents.CUSTOM_NAME, Component.literal("§8◀ İlk Sayfadasınız"));
            filterContainer.setItem(PREV_PAGE_SLOT, prevDisabled);
        }

        // Info Book (Slot 49)
        int activeCount = 0;
        for (String s : filterSlots) {
            if (s != null && !s.isEmpty()) activeCount++;
        }
        ItemStack info = new ItemStack(Items.BOOK);
        info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lSayfa " + (currentPage + 1) + " / " + TOTAL_PAGES));
        int pageStart = currentPage * SLOTS_PER_PAGE + 1;
        int pageEnd = Math.min((currentPage + 1) * SLOTS_PER_PAGE, TOTAL_FILTER_SLOTS);
        List<Component> infoLore = List.of(
                Component.literal("§7Toplam Filtre Kapasitesi: §f" + TOTAL_FILTER_SLOTS + " Slot"),
                Component.literal("§7Bu Sayfadaki Aralık: §e" + pageStart + " - " + pageEnd),
                Component.literal("§7Dolu Filtre Slotu: §a" + activeCount + " / " + TOTAL_FILTER_SLOTS)
        );
        info.set(DataComponents.LORE, new ItemLore(infoLore));
        filterContainer.setItem(INFO_SLOT, info);

        // Clear All Button (Slot 50)
        ItemStack clear = new ItemStack(Items.BARRIER);
        clear.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lTüm Filtreyi Temizle"));
        List<Component> clearLore = List.of(Component.literal("§7Asadaki tüm 100 filtre slotunu sıfırlar."));
        clear.set(DataComponents.LORE, new ItemLore(clearLore));
        filterContainer.setItem(CLEAR_FILTER_SLOT, clear);

        // Next Page Button (Slot 53)
        if (currentPage < TOTAL_PAGES - 1) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§aSonraki Sayfa (Sayfa " + (currentPage + 2) + ") ▶"));
            filterContainer.setItem(NEXT_PAGE_SLOT, next);
        } else {
            ItemStack nextDisabled = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            nextDisabled.set(DataComponents.CUSTOM_NAME, Component.literal("§8Son Sayfadasınız ▶"));
            filterContainer.setItem(NEXT_PAGE_SLOT, nextDisabled);
        }
    }

    public void saveFilterToWand() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < TOTAL_FILTER_SLOTS; i++) {
            if (filterSlots[i] != null && !filterSlots[i].isEmpty()) {
                if (!ids.contains(filterSlots[i])) {
                    ids.add(filterSlots[i]);
                }
            }
        }
        LogisticsWandManager.saveFilterItemIds(wandStack, ids);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId < 0) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        // 1. Filter Slots (0 to 44)
        if (slotId < SLOTS_PER_PAGE) {
            int globalIndex = currentPage * SLOTS_PER_PAGE + slotId;
            if (globalIndex >= TOTAL_FILTER_SLOTS) {
                // Locked slot
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
                return;
            }

            if (clickType == ClickType.THROW) {
                return;
            }

            if (clickType == ClickType.SWAP) {
                // Player pressed 1-9 to swap with hotbar
                if (button >= 0 && button < 9) {
                    ItemStack hotbarItem = player.getInventory().getItem(button);
                    if (!hotbarItem.isEmpty()) {
                        ItemStack filterStack = hotbarItem.copy();
                        filterStack.setCount(1);
                        filterContainer.setItem(slotId, filterStack);
                        filterSlots[globalIndex] = BuiltInRegistries.ITEM.getKey(filterStack.getItem()).toString();
                    } else {
                        filterContainer.setItem(slotId, ItemStack.EMPTY);
                        filterSlots[globalIndex] = null;
                    }
                }
            } else if (clickType == ClickType.QUICK_MOVE) {
                // Shift click on filter slot clears it
                filterContainer.setItem(slotId, ItemStack.EMPTY);
                filterSlots[globalIndex] = null;
            } else {
                // Normal click or Drag
                ItemStack carried = getCarried();
                if (!carried.isEmpty()) {
                    ItemStack filterStack = carried.copy();
                    filterStack.setCount(1);
                    filterContainer.setItem(slotId, filterStack);
                    filterSlots[globalIndex] = BuiltInRegistries.ITEM.getKey(filterStack.getItem()).toString();
                } else {
                    filterContainer.setItem(slotId, ItemStack.EMPTY);
                    filterSlots[globalIndex] = null;
                }
            }

            this.broadcastChanges();
            saveFilterToWand();
            updateToolbar();
            return;
        }

        // 2. Toolbar & Navigation Buttons (45 to 53)
        if (slotId < CONTAINER_SIZE) {
            if (slotId == PREV_PAGE_SLOT && currentPage > 0) {
                currentPage--;
                loadPage();
                player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.0f);
            } else if (slotId == NEXT_PAGE_SLOT && currentPage < TOTAL_PAGES - 1) {
                currentPage++;
                loadPage();
                player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.0f);
            } else if (slotId == CLEAR_FILTER_SLOT) {
                Arrays.fill(filterSlots, null);
                loadPage();
                saveFilterToWand();
                player.playNotifySound(SoundEvents.LAVA_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 1.2f);
            }
            this.broadcastChanges();
            return;
        }

        // 3. Player Inventory Slots (slotId >= 54)
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex >= CONTAINER_SIZE) {
            ItemStack clicked = this.slots.get(slotIndex).getItem();
            if (!clicked.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(clicked.getItem()).toString();
                // Check if already in filter
                for (int i = 0; i < TOTAL_FILTER_SLOTS; i++) {
                    if (id.equals(filterSlots[i])) {
                        return ItemStack.EMPTY;
                    }
                }
                // Find first empty slot among 0..99
                for (int i = 0; i < TOTAL_FILTER_SLOTS; i++) {
                    if (filterSlots[i] == null) {
                        filterSlots[i] = id;
                        int pageStart = currentPage * SLOTS_PER_PAGE;
                        int pageEnd = pageStart + SLOTS_PER_PAGE;
                        if (i >= pageStart && i < pageEnd) {
                            int slotInPage = i - pageStart;
                            ItemStack filterStack = clicked.copy();
                            filterStack.setCount(1);
                            filterContainer.setItem(slotInPage, filterStack);
                            this.broadcastChanges();
                        }
                        saveFilterToWand();
                        updateToolbar();
                        player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.5f);
                        break;
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        saveFilterToWand();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
