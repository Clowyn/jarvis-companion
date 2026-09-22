package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.storage.StorageManager;
import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles all /api/container/* REST API endpoints:
 * - POST /api/container/sort
 * - POST /api/container/transfer
 * - POST/GET /api/container/scan
 */
public class ContainerHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (JarvisHttpServer.handleCorsPreflight(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        MinecraftServer server = JarvisHttpServer.getServer();
        if (server == null) {
            JarvisHttpServer.sendError(exchange, 503, "Server not ready");
            return;
        }

        if (path.equals("/api/container/sort") || path.equals("/api/container/sort/")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleSort(exchange, server);
            return;
        }

        if (path.equals("/api/container/transfer") || path.equals("/api/container/transfer/")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleTransfer(exchange, server);
            return;
        }

        if (path.equals("/api/container/scan") || path.equals("/api/container/scan/")
                || path.equals("/api/container") || path.equals("/api/container/")) {
            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleScan(exchange, server);
            return;
        }

        if (path.equals("/api/container/organize") || path.equals("/api/container/organize/")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleOrganize(exchange, server);
            return;
        }

        if (path.equals("/api/container/markers") || path.equals("/api/container/markers/")) {
            handleMarkers(exchange, server);
            return;
        }

        JarvisHttpServer.sendError(exchange, 404, "Endpoint not found");
    }

    private void handleSort(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        if (!body.has("x") || !body.has("y") || !body.has("z")
                || !isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
            JarvisHttpServer.sendError(exchange, 400, "Missing or invalid coordinates (x, y, z) for container sort");
            return;
        }

        int x = body.get("x").getAsInt();
        int y = body.get("y").getAsInt();
        int z = body.get("z").getAsInt();
        BlockPos pos = new BlockPos(x, y, z);

        try {
            JsonObject result = ThreadHelper.supplyOnMain(server, () -> {
                ServerLevel level = resolveLevel(server, body);
                return StorageManager.sortContainer(level, pos);
            }, 5, TimeUnit.SECONDS);

            int status = (result.has("success") && result.get("success").getAsBoolean()) ? 200 : 400;
            JarvisHttpServer.sendJsonResponse(exchange, status, result);
        } catch (TimeoutException e) {
            JarvisHttpServer.sendError(exchange, 504, "Main server thread timed out during container sort");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error in container sort", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to sort container: " + e.getMessage());
        }
    }

    private void handleTransfer(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        BlockPos sourcePos = extractPos(body, "source");
        BlockPos targetPos = extractPos(body, "target");

        if (sourcePos == null) {
            JarvisHttpServer.sendError(exchange, 400, "Missing or invalid source coordinates for transfer");
            return;
        }
        if (targetPos == null) {
            JarvisHttpServer.sendError(exchange, 400, "Missing or invalid target coordinates for transfer");
            return;
        }

        String filter = body.has("filter") && !body.get("filter").isJsonNull()
                ? body.get("filter").getAsString() : "all";

        try {
            JsonObject result = ThreadHelper.supplyOnMain(server, () -> {
                ServerLevel level = resolveLevel(server, body);
                return StorageManager.transferItems(level, sourcePos, targetPos, filter);
            }, 5, TimeUnit.SECONDS);

            int status = (result.has("success") && result.get("success").getAsBoolean()) ? 200 : 400;
            JarvisHttpServer.sendJsonResponse(exchange, status, result);
        } catch (TimeoutException e) {
            JarvisHttpServer.sendError(exchange, 504, "Main server thread timed out during container transfer");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error in container transfer", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to transfer items: " + e.getMessage());
        }
    }

    private void handleScan(HttpExchange exchange, MinecraftServer server) throws IOException {
        int radius = 16;
        int x = 0, y = 64, z = 0;
        boolean hasPos = false;
        String playerParam = null;

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> q = JarvisHttpServer.parseQueryParams(exchange);
            if (q.containsKey("radius")) {
                try {
                    radius = Integer.parseInt(q.get("radius"));
                } catch (NumberFormatException ignored) {}
            }
            if (q.containsKey("x") && q.containsKey("y") && q.containsKey("z")) {
                try {
                    x = Integer.parseInt(q.get("x"));
                    y = Integer.parseInt(q.get("y"));
                    z = Integer.parseInt(q.get("z"));
                    hasPos = true;
                } catch (NumberFormatException ignored) {}
            }
            playerParam = q.get("player");
        } else {
            JsonObject body = parseJsonObject(exchange);
            if (body != null) {
                if (body.has("radius") && !body.get("radius").isJsonNull()) {
                    radius = body.get("radius").getAsInt();
                }
                if (body.has("x") && body.has("y") && body.has("z")) {
                    x = body.get("x").getAsInt();
                    y = body.get("y").getAsInt();
                    z = body.get("z").getAsInt();
                    hasPos = true;
                }
                if (body.has("player") && !body.get("player").isJsonNull()) {
                    playerParam = body.get("player").getAsString();
                }
            }
        }

        final boolean finalHasPos = hasPos;
        final int finalX = x;
        final int finalY = y;
        final int finalZ = z;
        final int finalRadius = radius;
        final String finalPlayer = playerParam;

        try {
            JsonObject result = ThreadHelper.supplyOnMain(server, () -> {
                ServerLevel level = server.overworld();
                BlockPos center;
                if (finalHasPos) {
                    center = new BlockPos(finalX, finalY, finalZ);
                } else if (finalPlayer != null && !finalPlayer.isBlank()) {
                    ServerPlayer p = server.getPlayerList().getPlayerByName(finalPlayer.trim());
                    if (p != null) {
                        level = p.serverLevel();
                        center = p.blockPosition();
                    } else {
                        center = level.getSharedSpawnPos();
                    }
                } else if (!server.getPlayerList().getPlayers().isEmpty()) {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    level = p.serverLevel();
                    center = p.blockPosition();
                } else {
                    center = level.getSharedSpawnPos();
                }
                return StorageManager.scanContainers(level, center, finalRadius);
            }, 10, TimeUnit.SECONDS);

            JarvisHttpServer.sendJsonResponse(exchange, 200, result);
        } catch (TimeoutException e) {
            JarvisHttpServer.sendError(exchange, 504, "Main server thread timed out during container scan");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error in container scan", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to scan containers: " + e.getMessage());
        }
    }

    private void handleOrganize(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        int x = 0, y = 64, z = 0;
        boolean hasPos = false;
        if (body.has("x") && body.has("y") && body.has("z")
                && isInteger(body.get("x")) && isInteger(body.get("y")) && isInteger(body.get("z"))) {
            x = body.get("x").getAsInt();
            y = body.get("y").getAsInt();
            z = body.get("z").getAsInt();
            hasPos = true;
        } else {
            BlockPos centerPos = extractPos(body, "center");
            if (centerPos != null) {
                x = centerPos.getX();
                y = centerPos.getY();
                z = centerPos.getZ();
                hasPos = true;
            }
        }

        int radius = 32;
        if (body.has("radius") && isInteger(body.get("radius"))) {
            radius = body.get("radius").getAsInt();
        }

        final boolean finalHasPos = hasPos;
        final int finalX = x;
        final int finalY = y;
        final int finalZ = z;
        final int finalRadius = radius;

        try {
            JsonObject result = ThreadHelper.supplyOnMain(server, () -> {
                ServerLevel level = resolveLevel(server, body);
                BlockPos center;
                if (finalHasPos) {
                    center = new BlockPos(finalX, finalY, finalZ);
                } else {
                    var companion = com.alcyone.jarvis.entity.CompanionManager.getInstance().getActiveCompanion();
                    if (companion != null && companion.isAlive()) {
                        level = (ServerLevel) companion.level();
                        center = companion.blockPosition();
                    } else if (!server.getPlayerList().getPlayers().isEmpty()) {
                        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        level = p.serverLevel();
                        center = p.blockPosition();
                    } else {
                        center = level.getSharedSpawnPos();
                    }
                }
                return StorageManager.organizeBaseStorage(level, center, finalRadius);
            }, 10, TimeUnit.SECONDS);

            int status = (result.has("success") && result.get("success").getAsBoolean()) ? 200 : 400;
            JarvisHttpServer.sendJsonResponse(exchange, status, result);
        } catch (TimeoutException e) {
            JarvisHttpServer.sendError(exchange, 504, "Main server thread timed out during storage organization");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error in storage organization", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to organize storage: " + e.getMessage());
        }
    }

    private BlockPos extractPos(JsonObject body, String key) {
        if (body.has(key) && body.get(key).isJsonObject()) {
            JsonObject obj = body.getAsJsonObject(key);
            if (obj.has("x") && obj.has("y") && obj.has("z")
                    && isInteger(obj.get("x")) && isInteger(obj.get("y")) && isInteger(obj.get("z"))) {
                return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
            }
        }
        // Fallback for flat keys like "source_x", "source_y", "source_z"
        String xKey = key + "_x";
        String yKey = key + "_y";
        String zKey = key + "_z";
        if (body.has(xKey) && body.has(yKey) && body.has(zKey)
                && isInteger(body.get(xKey)) && isInteger(body.get(yKey)) && isInteger(body.get(zKey))) {
            return new BlockPos(body.get(xKey).getAsInt(), body.get(yKey).getAsInt(), body.get(zKey).getAsInt());
        }
        return null;
    }

    private ServerLevel resolveLevel(MinecraftServer server, JsonObject body) {
        if (body.has("dimension") && !body.get("dimension").isJsonNull()) {
            String dim = body.get("dimension").getAsString().trim();
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().toString().equals(dim)) {
                    return level;
                }
            }
        }
        return server.overworld();
    }

    private JsonObject parseJsonObject(HttpExchange exchange) throws IOException {
        String rawBody;
        try {
            rawBody = JarvisHttpServer.readBody(exchange);
        } catch (JarvisHttpServer.PayloadTooLargeException e) {
            JarvisHttpServer.sendError(exchange, 413, "Payload Too Large");
            return null;
        }

        if (rawBody == null || rawBody.trim().isEmpty()) {
            return new JsonObject();
        }

        try {
            JsonElement parsed = JsonParser.parseString(rawBody);
            if (!parsed.isJsonObject()) {
                JarvisHttpServer.sendError(exchange, 400, "JSON payload must be an object");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            JarvisHttpServer.sendError(exchange, 400, "Malformed JSON syntax");
            return null;
        }
    }

    private static boolean isInteger(JsonElement elem) {
        if (elem == null || !elem.isJsonPrimitive()) return false;
        try {
            Long.parseLong(elem.getAsString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void handleMarkers(HttpExchange exchange, MinecraftServer server) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            JsonObject res = new JsonObject();
            res.addProperty("success", true);
            res.add("markers", StorageManager.getMarkedPositionsJson());
            JarvisHttpServer.sendJsonResponse(exchange, 200, res);
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            JsonObject body = parseJsonObject(exchange);
            if (body == null) return;
            String action = body.has("action") ? body.get("action").getAsString() : "get";
            if ("clear".equalsIgnoreCase(action)) {
                StorageManager.clearMarkers();
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                res.addProperty("message", "Markers cleared");
                JarvisHttpServer.sendJsonResponse(exchange, 200, res);
                return;
            }
            if ("add".equalsIgnoreCase(action) && body.has("x") && body.has("y") && body.has("z")) {
                int x = body.get("x").getAsInt();
                int y = body.get("y").getAsInt();
                int z = body.get("z").getAsInt();
                ServerLevel level = resolveLevel(server, body);
                StorageManager.toggleMarker(level, new BlockPos(x, y, z), null);
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                res.add("markers", StorageManager.getMarkedPositionsJson());
                JarvisHttpServer.sendJsonResponse(exchange, 200, res);
                return;
            }
        }
        JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
    }
}
