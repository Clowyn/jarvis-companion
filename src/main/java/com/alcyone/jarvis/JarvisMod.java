package com.alcyone.jarvis;

import com.alcyone.jarvis.chat.ChatHistory;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.server.tunnel.CloudflareTunnel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(JarvisMod.MODID)
public class JarvisMod {
    public static final String MODID = "jarvis";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public static final int HTTP_PORT = 25585;

    public static int getHttpPort() {
        String portProp = System.getProperty("jarvis.port");
        if (portProp != null && !portProp.isBlank()) {
            try {
                return Integer.parseInt(portProp.trim());
            } catch (NumberFormatException ignored) {}
        }
        String portEnv = System.getenv("JARVIS_PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {}
        }
        return HTTP_PORT;
    }

    public static boolean isTunnelEnabled(boolean isDedicatedServer) {
        String tunnelProp = System.getProperty("jarvis.tunnel");
        if (tunnelProp != null && !tunnelProp.isBlank()) {
            return Boolean.parseBoolean(tunnelProp.trim());
        }
        String tunnelEnv = System.getenv("JARVIS_TUNNEL");
        if (tunnelEnv != null && !tunnelEnv.isBlank()) {
            return Boolean.parseBoolean(tunnelEnv.trim());
        }
        // Enabled by default on dedicated servers (like Exaroton), disabled on local singleplayer
        return isDedicatedServer;
    }

    public JarvisMod(IEventBus modEventBus) {
        LOGGER.info("[Jarvis] Initializing Jarvis Companion Mod...");
        com.alcyone.jarvis.entity.ModEntities.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);

        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(com.alcyone.jarvis.client.JarvisClientMod::registerRenderers);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        int port = getHttpPort();
        LOGGER.info("[Jarvis] Server is starting, launching Jarvis HTTP Server on port {}...", port);
        JarvisHttpServer.start(event.getServer(), port);

        boolean dedicated = event.getServer().isDedicatedServer();
        if (isTunnelEnabled(dedicated)) {
            LOGGER.info("[Jarvis] Dedicated server detected or tunnel enabled: launching Cloudflare Quick Tunnel...");
            CloudflareTunnel.start(port);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[Jarvis] Server is stopping, shutting down Jarvis HTTP Server...");
        CloudflareTunnel.stop();
        JarvisHttpServer.stop();
        com.alcyone.jarvis.entity.CompanionManager.getInstance().reset();
    }

    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        // Main command: /jarvis url
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("jarvis")
                        .then(net.minecraft.commands.Commands.literal("url")
                                .executes(ctx -> {
                                    String url = CloudflareTunnel.getActiveUrl();
                                    if (url != null) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("[Jarvis] Public API URL: ").withStyle(ChatFormatting.GOLD)
                                                .append(Component.literal(url).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.AQUA)), false);
                                    } else {
                                        ctx.getSource().sendSuccess(() -> Component.literal("[Jarvis] Cloudflare tunnel is not active (Local port: " + getHttpPort() + ")")
                                                .withStyle(ChatFormatting.GRAY), false);
                                    }
                                    return 1;
                                }))
        );
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getRawText();
        String playerName = player.getScoreboardName();
        String trimmed = message.trim();

        ChatHistory.add(playerName, message);

        // In-game command to quickly query active Cloudflare URL
        if (trimmed.equalsIgnoreCase("!jarvis url") || trimmed.equalsIgnoreCase("!jarvis link")) {
            String url = CloudflareTunnel.getActiveUrl();
            if (url != null) {
                player.sendSystemMessage(Component.literal("[Jarvis] Public API URL: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(url).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.AQUA)));
            } else {
                player.sendSystemMessage(Component.literal("[Jarvis] Cloudflare tunnel is not active (Local port: " + getHttpPort() + ")")
                        .withStyle(ChatFormatting.GRAY));
            }
            return;
        }

        // In-game command to reset wand source chest
        if (trimmed.equalsIgnoreCase("!jarvis wand reset") || trimmed.equalsIgnoreCase("!jarvis reset wand")) {
            com.alcyone.jarvis.storage.LogisticsWandManager.clearSourcePos(player.getMainHandItem(), player);
            player.sendSystemMessage(Component.literal("§e[Jarvis] Asanın kaynak sandık seçimi sıfırlandı."));
            return;
        }

        // If player asks Jarvis something directly in chat
        if (message.startsWith("!jarvis ") || message.startsWith("@jarvis ")) {
            LOGGER.info("[Jarvis] Direct message from {}: {}", playerName, message);
        }
    }

    @SubscribeEvent
    public void onPlayerRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        net.minecraft.world.item.ItemStack item = event.getItemStack();

        // 1. Jarvis Logistics Wand Handler
        if (com.alcyone.jarvis.storage.LogisticsWandManager.isWand(item)) {
            net.minecraft.core.BlockPos clickedPos = event.getPos();
            net.minecraft.world.level.block.state.BlockState state = serverLevel.getBlockState(clickedPos);
            net.minecraft.world.level.block.entity.BlockEntity be = serverLevel.getBlockEntity(clickedPos);

            if (com.alcyone.jarvis.storage.StorageManager.isStorageContainer(state, be)
                    || com.alcyone.jarvis.storage.StorageManager.getItemHandler(serverLevel, clickedPos) != null) {
                com.alcyone.jarvis.storage.LogisticsWandManager.handleChestClick(player, serverLevel, clickedPos, event.getFace(), item, player.isShiftKeyDown());
                player.containerMenu.broadcastChanges();
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            } else if (!player.isShiftKeyDown()) {
                // Right click on floor / non-container: open wand filter menu
                com.alcyone.jarvis.storage.LogisticsWandManager.openWandFilterMenu(player, item);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            } else {
                // Shift + Right click on non-container: safely cancel without wiping source
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }
        }

        // 2. Fallback marker toggle for standard stick
        if (item.is(net.minecraft.world.item.Items.STICK)) {
            if (player.isShiftKeyDown()) {
                net.minecraft.core.BlockPos clickedPos = event.getPos();
                net.minecraft.core.BlockPos targetPos = clickedPos.relative(event.getFace());
                com.alcyone.jarvis.storage.StorageManager.toggleMarker(serverLevel, targetPos, player);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerRightClickItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        net.minecraft.world.item.ItemStack item = event.getItemStack();
        if (com.alcyone.jarvis.storage.LogisticsWandManager.isWand(item)) {
            com.alcyone.jarvis.storage.LogisticsWandManager.openWandFilterMenu(player, item);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerLeftClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        net.minecraft.world.item.ItemStack item = event.getItemStack();
        if (com.alcyone.jarvis.storage.LogisticsWandManager.isWand(item) && player.isShiftKeyDown()) {
            com.alcyone.jarvis.storage.LogisticsWandManager.clearSourcePos(item, player);
            player.containerMenu.broadcastChanges();
            event.setCanceled(true);
        }
    }
}
