package com.alcyone.jarvis.gui;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.storage.StorageManager;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive In-Game Control Panel & Command GUI Menu for Jarvis Companion.
 * Allows right-click interaction directly in Minecraft without requiring the Python chat bridge.
 * Fully server-authoritative, zero item voiding, and zero ghost items.
 */
public class JarvisControlMenu extends ChestMenu {

    private final JarvisCompanionEntity companion;
    private final SimpleContainer controlContainer;

    public JarvisControlMenu(int containerId, Inventory playerInventory, JarvisCompanionEntity companion) {
        this(containerId, playerInventory, new SimpleContainer(27), companion);
    }

    private JarvisControlMenu(int containerId, Inventory playerInventory, SimpleContainer controlContainer, JarvisCompanionEntity companion) {
        super(MenuType.GENERIC_9x3, containerId, playerInventory, controlContainer, 3);
        this.companion = companion;
        this.controlContainer = controlContainer;
        setupButtons();
    }

    /**
     * Builds and updates all interactive button slots in the 9x3 menu.
     */
    public void setupButtons() {
        ItemStack filler = createItem(Items.GRAY_STAINED_GLASS_PANE, "§r ", List.of());
        for (int i = 0; i < 27; i++) {
            controlContainer.setItem(i, filler.copy());
        }

        if (companion == null || !companion.isAlive()) {
            controlContainer.setItem(13, createItem(Items.BARRIER, "§c§lJARVIS Aktif Değil", List.of(Component.literal("§7Companion canlı değil."))));
            return;
        }

        // Slot 4: Telemetry & Status Overview
        String stateName = companion.getCompanionState() != null ? companion.getCompanionState().name() : "IDLE";
        int health = (int) companion.getHealth();
        int maxHealth = (int) companion.getMaxHealth();
        int occSlots = countOccupiedInventorySlots();

        List<Component> headerLore = new ArrayList<>();
        headerLore.add(Component.literal("§7Can: §c" + health + "§7/§c" + maxHealth + " HP"));
        headerLore.add(Component.literal("§7Mod: §a" + stateName + (companion.isGuardMode() ? " §e[KORUMA]" : "") + (companion.getSentryPost() != null ? " §b[NÖBET]" : "")));
        headerLore.add(Component.literal("§7Konum: §eX:" + (int)companion.getX() + " Y:" + (int)companion.getY() + " Z:" + (int)companion.getZ()));
        headerLore.add(Component.literal("§7Çanta: §b" + occSlots + "/27 Yuva Dolu"));
        headerLore.add(Component.literal("§8§m------------------------"));
        headerLore.add(Component.literal("§7Tüm protokolleri aşağıdaki butonlardan"));
        headerLore.add(Component.literal("§7tek tıkla yönetebilirsiniz Efendim."));
        controlContainer.setItem(4, createItem(Items.NETHER_STAR, "§6§lJARVIS PROTOKOL MERKEZİ", headerLore));

        // Slot 10: Follow / Stop
        boolean isFollowing = companion.getCompanionState() == JarvisCompanionEntity.State.FOLLOWING;
        Item followItem = isFollowing ? Items.LIME_DYE : Items.COMPASS;
        String followTitle = isFollowing ? "§a§lTakip Ediyor §7(Tıkla: Durdur)" : "§e§lBekliyor §7(Tıkla: Takip Et)";
        controlContainer.setItem(10, createItem(followItem, followTitle, List.of(
            Component.literal("§7Jarvis'in sizi takip etmesini veya"),
            Component.literal("§7olduğu yerde beklemesini sağlar.")
        )));

        // Slot 11: Bodyguard Mode
        boolean isGuarding = companion.isGuardMode();
        Item guardItem = isGuarding ? Items.SHIELD : Items.IRON_BARS;
        String guardTitle = isGuarding ? "§9§lKorumalık: §aAKTİF §7(Tıkla: Kapat)" : "§9§lKorumalık: §cPASİF §7(Tıkla: Aç)";
        controlContainer.setItem(11, createItem(guardItem, guardTitle, List.of(
            Component.literal("§7Aktifken size saldıran veya sizin"),
            Component.literal("§7saldırdığınız hedeflere anında karşılık verir.")
        )));

        // Slot 12: Sentry Mode
        boolean isSentry = companion.getSentryPost() != null;
        Item sentryItem = isSentry ? Items.BELL : Items.IRON_SWORD;
        String sentryTitle = isSentry ? "§c§lNöbetçi: §aBÖLGEDE NÖBETTE §7(Tıkla: Bitir)" : "§c§lNöbetçi Modu §7(Tıkla: Nöbete Geç)";
        controlContainer.setItem(12, createItem(sentryItem, sentryTitle, List.of(
            Component.literal("§7Bulunulan noktayı merkez alarak"),
            Component.literal("§732 blok çevredeki canavarları temizler.")
        )));

        // Slot 13: Organize Storage & Stack Double Chests
        controlContainer.setItem(13, createItem(Items.CHEST, "§6§lDepoyu Düzenle & Kat Çık", List.of(
            Component.literal("§bMadenler, yemekler, bloklar, ekipmanlar,"),
            Component.literal("§btarım ve malzemeleri ayrı sandıklara ayırır."),
            Component.literal("§6Dolan sandıkların üzerine Double Chest katı çıkar!"),
            Component.literal("§eTıkla: Protokolü anında çalıştır.")
        )));

        // Slot 14: Sort & Compact Containers
        controlContainer.setItem(14, createItem(Items.HOPPER, "§e§lSandıkları Sırala", List.of(
            Component.literal("§732 blok çevredeki sandıkları yerinde"),
            Component.literal("§7birleştirir, kompaktlaştırır ve sıralar.")
        )));

        // Slot 15: Scan Nearby Ores
        controlContainer.setItem(15, createItem(Items.DIAMOND_PICKAXE, "§b§lMaden Taraması", List.of(
            Component.literal("§732 blok yarıçapındaki madenleri"),
            Component.literal("§7tespit edip özetini chat ekranına yazar.")
        )));

        // Slot 16: Eat Food & Heal
        controlContainer.setItem(16, createItem(Items.COOKED_BEEF, "§d§lYemek Ye & İyileş", List.of(
            Component.literal("§7Jarvis'in canını tamamen doldurur,"),
            Component.literal("§7rejenerasyon ve beslenme efekti uygular.")
        )));

        // Slot 19: Emergency Shelter Build
        controlContainer.setItem(19, createItem(Items.BRICKS, "§6§lHızlı İnşaat: Acil Barınak", List.of(
            Component.literal("§7Bulunulan noktada 5x5 güvenli, ışıklı"),
            Component.literal("§7ve kapılı acil durum sığınağı inşa eder."),
            Component.literal("§eTıkla: İnşaatı başlat")
        )));

        // Slot 20: Open Real 27-slot Internal Inventory
        controlContainer.setItem(20, createItem(Items.BARREL, "§6§lDahili Çantayı Aç (27 Yuva)", List.of(
            Component.literal("§eJarvis'in kendi sırt çantasını açar."),
            Component.literal("§7Zırh, alet ve ganimet transferi yapabilirsiniz."),
            Component.literal("§8(İpucu: Shift+Sağ Tık ile de doğrudan açılır)")
        )));

        // Slot 21: Toggle Time (Day / Night)
        controlContainer.setItem(21, createItem(Items.CLOCK, "§e§lZamanı Değiştir (Gündüz / Gece)", List.of(
            Component.literal("§7Dünya zamanını sabah veya gece yapar.")
        )));

        // Slot 22: Telemetry Detailed Report
        controlContainer.setItem(22, createItem(Items.WRITABLE_BOOK, "§a§lDetaylı Durum Raporu", List.of(
            Component.literal("§7Sistem durumunu, zırh puanını ve"),
            Component.literal("§7çevre tehditlerini chat ekranına yazdırır.")
        )));

        // Slot 23: Bridge Build
        controlContainer.setItem(23, createItem(Items.OAK_FENCE, "§e§lHızlı İnşaat: Yürüyüş Köprüsü", List.of(
            Component.literal("§7Baktığınız yöne doğru 12 blok"),
            Component.literal("§7korkuluklu ve meşaleli yürüyüş köprüsü kurar."),
            Component.literal("§eTıkla: İnşaatı başlat")
        )));

        // Slot 24: Close Menu
        controlContainer.setItem(24, createItem(Items.BARRIER, "§c§lMenüyü Kapat", List.of(
            Component.literal("§7Kontrol panelinden çıkış yapar.")
        )));
    }

    private int countOccupiedInventorySlots() {
        if (companion == null) return 0;
        int count = 0;
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) count++;
        }
        return count;
    }

    private ItemStack createItem(Item item, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Intercept top 27 control slots - NEVER permit item extraction
        if (slotId >= 0 && slotId < 27) {
            if (player instanceof ServerPlayer serverPlayer) {
                handleActionClick(slotId, serverPlayer);
                setupButtons();
                this.broadcastChanges();
            }
            return;
        }

        // Standard player inventory slot clicks below
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        // Prevent shift-clicking player items into the control panel
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return companion != null && companion.isAlive() && player.distanceToSqr(companion) <= 64.0D;
    }

    private void handleActionClick(int slotId, ServerPlayer player) {
        if (companion == null || !companion.isAlive()) return;
        ServerLevel level = (ServerLevel) companion.level();

        switch (slotId) {
            case 10 -> { // Follow / Stop
                if (companion.getCompanionState() == JarvisCompanionEntity.State.FOLLOWING) {
                    companion.stopAllMovement();
                    player.displayClientMessage(Component.literal("§c[Jarvis] Bekleme moduna geçildi. Yanınızda bekliyorum Efendim."), true);
                    player.playNotifySound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.8f, 1.2f);
                } else {
                    companion.setFollowTarget(player, 2.5);
                    player.displayClientMessage(Component.literal("§a[Jarvis] Takip protokolü aktif. Sizi takip ediyorum Efendim."), true);
                    player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
                }
            }
            case 11 -> { // Guard Mode
                boolean nextGuard = !companion.isGuardMode();
                companion.setGuardMode(nextGuard);
                if (nextGuard) {
                    player.displayClientMessage(Component.literal("§a[Jarvis] Korumalık protokolü AÇIK Efendim."), true);
                    player.playNotifySound(SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.9f, 1.2f);
                } else {
                    player.displayClientMessage(Component.literal("§c[Jarvis] Korumalık protokolü KAPALI Efendim."), true);
                    player.playNotifySound(SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 0.8f, 1.0f);
                }
            }
            case 12 -> { // Sentry Mode
                if (companion.getSentryPost() != null) {
                    companion.setSentryPost(null);
                    companion.setFollowTarget(player, 2.5);
                    player.displayClientMessage(Component.literal("§c[Jarvis] Nöbet sona erdi. Sizi takip ediyorum Efendim."), true);
                    player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
                } else {
                    companion.setSentryPost(companion.blockPosition());
                    companion.stopAllMovement();
                    player.displayClientMessage(Component.literal("§e[Jarvis] Bu konumda (32 blok) nöbetçi devriyesi başlatıldı Efendim."), true);
                    player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.9f, 1.5f);
                }
            }
            case 13 -> { // Organize Storage & Stack Double Chests
                player.displayClientMessage(Component.literal("§6[Jarvis] Depo organizasyonu başlatılıyor..."), true);
                JsonObject res = StorageManager.organizeBaseStorage(level, companion.blockPosition(), 32);
                boolean success = res.has("success") && res.get("success").getAsBoolean();
                if (success) {
                    int total = res.get("total_items_organized").getAsInt();
                    int newChests = res.get("new_chests_placed").getAsInt();
                    String stackMsg = newChests > 0 ? (" §e(" + newChests + " yeni double chest katı eklendi)") : "";
                    player.sendSystemMessage(Component.literal("§a§l[Jarvis] Depo düzenlendi! §fToplam " + total + " eşya kategorilerine ayrıldı." + stackMsg));
                    player.playNotifySound(SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.0f, 1.0f);
                    player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.4f);
                } else {
                    String err = res.has("error") ? res.get("error").getAsString() : "Yakında sandık bulunamadı";
                    player.displayClientMessage(Component.literal("§c[Jarvis] " + err), false);
                    player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.9f, 1.0f);
                }
            }
            case 14 -> { // Sort & Compact Nearby Containers
                var candidates = StorageManager.findContainers(level, companion.blockPosition(), 32);
                int sorted = 0;
                for (var c : candidates) {
                    JsonObject sRes = StorageManager.sortContainer(level, c.pos);
                    if (sRes.has("success") && sRes.get("success").getAsBoolean()) {
                        sorted++;
                    }
                }
                player.displayClientMessage(Component.literal("§e[Jarvis] " + sorted + " adet sandık yerinde kompaktlaştırılıp sıralandı."), false);
                player.playNotifySound(SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.9f, 1.2f);
            }
            case 15 -> { // Scan Ores
                BlockPos pos = companion.blockPosition();
                Map<String, Integer> ores = new HashMap<>();
                int rad = 32;
                for (BlockPos p : BlockPos.betweenClosed(pos.offset(-rad, -16, -rad), pos.offset(rad, 16, rad))) {
                    BlockState bs = level.getBlockState(p);
                    if (bs.is(Tags.Blocks.ORES) || bs.getBlock().getDescriptionId().contains("ore")) {
                        String name = bs.getBlock().getName().getString();
                        ores.put(name, ores.getOrDefault(name, 0) + 1);
                    }
                }
                if (!ores.isEmpty()) {
                    StringBuilder sb = new StringBuilder("§b§l[Jarvis] 32 Blok Maden Taraması:§r ");
                    ores.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(6)
                        .forEach(e -> sb.append("§e").append(e.getValue()).append("x §f").append(e.getKey()).append("§7, "));
                    player.sendSystemMessage(Component.literal(sb.toString().replaceAll(", $", "")));
                    player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
                } else {
                    player.displayClientMessage(Component.literal("§7[Jarvis] 32 blok çevrede maden tespit edilemedi."), true);
                }
            }
            case 16 -> { // Eat Food & Heal
                companion.setHealth(companion.getMaxHealth());
                companion.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, 1));
                companion.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 1));
                level.sendParticles(ParticleTypes.HEART, companion.getX(), companion.getY() + 1.5, companion.getZ(), 8, 0.5, 0.5, 0.5, 0.0);
                player.playNotifySound(SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 1.0f, 1.0f);
                player.displayClientMessage(Component.literal("§a[Jarvis] Canım tamamen dolduruldu Efendim."), true);
            }
            case 19 -> { // Emergency Shelter Build
                player.closeContainer();
                net.minecraft.core.Direction dir = companion.getDirection();
                int size = 5;
                BlockPos center = companion.blockPosition().relative(dir, (size / 2) + 2);
                com.alcyone.jarvis.building.BuildTask task = com.alcyone.jarvis.building.StructureGenerators.createShelter(
                    level, center, size, 3, "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:cobblestone", true, true
                );
                com.alcyone.jarvis.building.BuildManager.getInstance().startTask(task);
                player.displayClientMessage(Component.literal("§6[Jarvis] Acil durum sığınağı inşasına başlanıyor Efendim (" + task.getTotalBlocks() + " blok)."), true);
                player.playNotifySound(SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            case 20 -> { // Open 27-slot Backpack
                player.closeContainer();
                companion.openInventory(player);
            }
            case 21 -> { // Toggle Time
                long time = level.getDayTime();
                long newTime = (time % 24000 < 12000) ? ((time / 24000) * 24000 + 13000) : ((time / 24000 + 1) * 24000 + 1000);
                level.setDayTime(newTime);
                String timeLabel = (newTime % 24000 < 12000) ? "Sabah" : "Gece";
                player.displayClientMessage(Component.literal("§e[Jarvis] Zaman " + timeLabel + " olarak ayarlandı Efendim."), true);
                player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
            }
            case 22 -> { // Telemetry Report
                player.sendSystemMessage(Component.literal("§6§m========================================"));
                player.sendSystemMessage(Component.literal("§6§lJARVIS SİSTEM & TELEMETRİ RAPORU"));
                player.sendSystemMessage(Component.literal("§7Can: §c" + (int)companion.getHealth() + "§7/§c" + (int)companion.getMaxHealth() + " HP"));
                player.sendSystemMessage(Component.literal("§7Mod: §a" + companion.getCompanionState() + (companion.isGuardMode() ? " §e[KORUMA AKTİF]" : "") + (companion.getSentryPost() != null ? " §b[NÖBETTE]" : "")));
                player.sendSystemMessage(Component.literal("§7Konum: §eX: " + (int)companion.getX() + ", Y: " + (int)companion.getY() + ", Z: " + (int)companion.getZ()));
                player.sendSystemMessage(Component.literal("§7Ana El: §b" + (companion.getMainHandItem().isEmpty() ? "Boş" : companion.getMainHandItem().getHoverName().getString())));
                player.sendSystemMessage(Component.literal("§7İkinci El: §b" + (companion.getOffhandItem().isEmpty() ? "Boş" : companion.getOffhandItem().getHoverName().getString())));
                player.sendSystemMessage(Component.literal("§6§m========================================"));
                player.playNotifySound(SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
            case 23 -> { // Bridge Build
                player.closeContainer();
                net.minecraft.core.Direction dir = player.getDirection();
                BlockPos start = companion.blockPosition().relative(dir);
                com.alcyone.jarvis.building.BuildTask task = com.alcyone.jarvis.building.StructureGenerators.createBridge(
                    level, start, dir, 12, 3, "minecraft:cobblestone", true, true
                );
                com.alcyone.jarvis.building.BuildManager.getInstance().startTask(task);
                player.displayClientMessage(Component.literal("§e[Jarvis] 12 blokluk yürüyüş köprüsü inşasına başlanıyor Efendim (" + task.getTotalBlocks() + " blok)."), true);
                player.playNotifySound(SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            case 24 -> { // Close Menu
                player.closeContainer();
            }
            default -> {}
        }
    }
}
