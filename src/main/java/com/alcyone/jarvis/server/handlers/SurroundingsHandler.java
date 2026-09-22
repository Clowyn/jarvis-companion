package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.perception.SurroundingsScanner;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles GET /api/surroundings.
 * High-performance world perception endpoint backed by SurroundingsScanner,
 * featuring 3-stage chunk section pruning, tag classification, and a 500ms perception cache.
 */
public class SurroundingsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    private record ScanOutcome(int statusCode, String errorMessage, JsonObject result) {}

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
            JarvisHttpServer.sendError(exchange, 503, "Minecraft server is not ready or offline");
            return;
        }

        Map<String, String> params = JarvisHttpServer.parseQueryParams(exchange);
        String targetPlayerName = params.get("player");
        int targetRadius = SurroundingsScanner.DEFAULT_RADIUS;
        if (params.containsKey("radius")) {
            try {
                int parsed = Integer.parseInt(params.get("radius"));
                targetRadius = SurroundingsScanner.clampRadius(parsed);
            } catch (NumberFormatException e) {
                targetRadius = SurroundingsScanner.DEFAULT_RADIUS;
            }
        }

        final int radius = targetRadius;

        try {
            ScanOutcome outcome = ThreadHelper.supplyOnMain(server, () -> {
                com.alcyone.jarvis.entity.JarvisCompanionEntity companion =
                        com.alcyone.jarvis.entity.CompanionManager.getInstance().getActiveCompanion();
                boolean useCompanion = "true".equalsIgnoreCase(params.get("companion"))
                        || "companion".equalsIgnoreCase(params.get("origin"));

                if (useCompanion && companion != null && companion.isAlive()) {
                    JsonObject result = SurroundingsScanner.scan((net.minecraft.server.level.ServerLevel) companion.level(),
                            companion.getX(), companion.getY(), companion.getZ(), radius, companion);
                    return new ScanOutcome(200, null, result);
                }

                if (params.containsKey("x") && params.containsKey("y") && params.containsKey("z")) {
                    try {
                        double ox = Double.parseDouble(params.get("x"));
                        double oy = Double.parseDouble(params.get("y"));
                        double oz = Double.parseDouble(params.get("z"));
                        net.minecraft.server.level.ServerLevel lvl = companion != null ?
                                (net.minecraft.server.level.ServerLevel) companion.level() : server.overworld();
                        JsonObject result = SurroundingsScanner.scan(lvl, ox, oy, oz, radius, null);
                        return new ScanOutcome(200, null, result);
                    } catch (NumberFormatException ignored) {
                    }
                }

                ServerPlayer player = null;
                if (targetPlayerName != null && !targetPlayerName.trim().isEmpty()) {
                    player = server.getPlayerList().getPlayerByName(targetPlayerName.trim());
                    if (player == null) {
                        return new ScanOutcome(404, "Player '" + targetPlayerName + "' not found", null);
                    }
                } else {
                    List<ServerPlayer> players = server.getPlayerList().getPlayers();
                    if (players.isEmpty()) {
                        return new ScanOutcome(404, "No players online", null);
                    }
                    player = players.get(0);
                }

                JsonObject result = SurroundingsScanner.getOrScanPlayer(server, player, radius);
                return new ScanOutcome(200, null, result);
            }, 10, TimeUnit.SECONDS);

            if (outcome.statusCode() != 200) {
                JarvisHttpServer.sendError(exchange, outcome.statusCode(), outcome.errorMessage());
                return;
            }

            JarvisHttpServer.sendJsonResponse(exchange, 200, outcome.result());
        } catch (TimeoutException e) {
            LOGGER.warn("[Jarvis] Surroundings scan timed out on main thread");
            JarvisHttpServer.sendError(exchange, 504, "Surroundings scan timed out on main server thread");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error in surroundings scan", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to scan surroundings: " + e.getMessage());
        }
    }
}
