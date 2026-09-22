package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles GET /api/status.
 * Returns server health, world time, and player telemetry.
 * Safe from ConcurrentModificationException via ThreadHelper.supplyOnMain.
 */
public class StatusHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (JarvisHttpServer.handleCorsPreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JarvisHttpServer.sendError(exchange, 405, "Method not allowed, use GET");
            return;
        }

        MinecraftServer server = JarvisHttpServer.getServer();
        if (server == null) {
            JsonObject offline = new JsonObject();
            offline.addProperty("status", "offline");
            offline.addProperty("error", "Minecraft server is not ready or offline");
            JarvisHttpServer.sendJsonResponse(exchange, 503, offline);
            return;
        }

        try {
            JsonObject telemetry = ThreadHelper.supplyOnMain(server, () -> {
                ServerLevel overworld = server.overworld();
                long worldTime = overworld != null ? overworld.getGameTime() : 0;
                long dayTime = overworld != null ? (overworld.getDayTime() % 24000) : 0;
                int playerCount = server.getPlayerCount();
                int maxPlayers = server.getMaxPlayers();

                JsonArray playersArray = new JsonArray();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", player.getScoreboardName());
                    p.addProperty("uuid", player.getStringUUID());
                    p.addProperty("dimension", player.level().dimension().location().toString());
                    p.addProperty("health", player.getHealth());
                    p.addProperty("max_health", player.getMaxHealth());
                    p.addProperty("food_level", player.getFoodData().getFoodLevel());
                    // Backwards compatibility fields
                    p.addProperty("food", player.getFoodData().getFoodLevel());

                    JsonObject pos = new JsonObject();
                    pos.addProperty("x", player.getX());
                    pos.addProperty("y", player.getY());
                    pos.addProperty("z", player.getZ());
                    p.add("position", pos);

                    p.addProperty("x", player.getX());
                    p.addProperty("y", player.getY());
                    p.addProperty("z", player.getZ());
                    p.addProperty("yaw", player.getYRot());
                    p.addProperty("pitch", player.getXRot());

                    playersArray.add(p);
                }

                JsonObject root = new JsonObject();
                root.addProperty("status", "online");
                root.addProperty("version", "1.21.1");
                String publicUrl = com.alcyone.jarvis.server.tunnel.CloudflareTunnel.getActiveUrl();
                if (publicUrl != null) {
                    root.addProperty("public_url", publicUrl);
                }
                root.addProperty("world_time", worldTime);
                root.addProperty("day_time", dayTime);
                root.addProperty("game_time", worldTime);
                root.addProperty("player_count", playerCount);
                root.addProperty("max_players", maxPlayers);
                root.add("players", playersArray);
                return root;
            }, 3, TimeUnit.SECONDS);

            JarvisHttpServer.sendJsonResponse(exchange, 200, telemetry);
        } catch (TimeoutException e) {
            LOGGER.warn("[Jarvis] Telemetry query timed out on main thread");
            JarvisHttpServer.sendError(exchange, 504, "Server tick timed out querying status");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error querying server status", e);
            JarvisHttpServer.sendError(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }
}
