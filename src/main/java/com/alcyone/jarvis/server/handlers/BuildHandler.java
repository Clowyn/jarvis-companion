package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.building.BuildManager;
import com.alcyone.jarvis.building.BuildTask;
import com.alcyone.jarvis.building.StructureGenerators;
import com.alcyone.jarvis.entity.CompanionManager;
import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Handles autonomous construction REST endpoints:
 * - POST /api/companion/build
 * - GET  /api/companion/build/status
 * - POST /api/companion/build/cancel
 */
public class BuildHandler implements HttpHandler {

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
            JarvisHttpServer.sendError(exchange, 503, "Minecraft server is not ready");
            return;
        }

        if (path.endsWith("/cancel")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleCancel(exchange);
            return;
        }

        if (path.endsWith("/status")) {
            if (!"GET".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleStatus(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            handleStatus(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            handleBuild(exchange);
            return;
        }

        JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        BuildTask active = BuildManager.getInstance().getActiveTask();
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);

        if (active != null) {
            resp.addProperty("active", !active.isCompleted() && !active.isCancelled());
            resp.add("task", active.toJson());
        } else {
            resp.addProperty("active", false);
            resp.add("task", null);
        }

        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        boolean cancelled = BuildManager.getInstance().cancelTask();
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("cancelled", cancelled);
        resp.addProperty("message", cancelled ? "Build task cancelled successfully" : "No active build task to cancel");
        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }

    private void handleBuild(HttpExchange exchange) throws IOException {
        JarvisCompanionEntity companion = CompanionManager.getInstance().getActiveCompanion();
        if (companion == null) {
            JarvisHttpServer.sendError(exchange, 400, "Companion is not currently spawned in the world");
            return;
        }

        String body = JarvisHttpServer.readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            JarvisHttpServer.sendError(exchange, 400, "Request body cannot be empty");
            return;
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            JarvisHttpServer.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        if (!json.has("structure")) {
            JarvisHttpServer.sendError(exchange, 400, "Missing required parameter 'structure'");
            return;
        }

        String structureType = json.get("structure").getAsString().trim().toLowerCase();
        String material = json.has("material") ? json.get("material").getAsString().trim() : "minecraft:cobblestone";

        ServerLevel level = (ServerLevel) companion.level();
        BlockPos companionPos = companion.blockPosition();

        Direction direction = companion.getDirection();
        if (json.has("direction")) {
            String dirStr = json.get("direction").getAsString().trim().toLowerCase();
            Direction parsed = Direction.byName(dirStr);
            if (parsed != null && parsed.getAxis().isHorizontal()) {
                direction = parsed;
            }
        }

        BuildTask task;
        switch (structureType) {
            case "bridge": {
                int length = json.has("length") ? json.get("length").getAsInt() : 10;
                int width = json.has("width") ? json.get("width").getAsInt() : 3;
                boolean railing = !json.has("railing") || json.get("railing").getAsBoolean();
                boolean torches = !json.has("torches") || json.get("torches").getAsBoolean();
                BlockPos bridgeStart = companionPos.relative(direction);
                task = StructureGenerators.createBridge(level, bridgeStart, direction, length, width, material, railing, torches);
                break;
            }

            case "shelter":
            case "bunker":
            case "house": {
                int size = json.has("size") ? json.get("size").getAsInt() : 5;
                int height = json.has("height") ? json.get("height").getAsInt() : 3;
                boolean door = !json.has("door") || json.get("door").getAsBoolean();
                boolean sTorches = !json.has("torches") || json.get("torches").getAsBoolean();
                String wallMat = json.has("wall_material") ? json.get("wall_material").getAsString().trim() : material;
                String floorMat = json.has("floor_material") ? json.get("floor_material").getAsString().trim() : "minecraft:oak_planks";
                String roofMat = json.has("roof_material") ? json.get("roof_material").getAsString().trim() : material;

                int radius = size / 2;
                BlockPos shelterCenter = companionPos.relative(direction, radius + 2);
                task = StructureGenerators.createShelter(level, shelterCenter, size, height, wallMat, floorMat, roofMat, door, sTorches);
                break;
            }

            case "wall":
            case "barricade": {
                int wallLen = json.has("length") ? json.get("length").getAsInt() : 7;
                int wallHeight = json.has("height") ? json.get("height").getAsInt() : 3;
                boolean crenellations = !json.has("crenellations") || json.get("crenellations").getAsBoolean();
                BlockPos wallCenter = companionPos.relative(direction, 2);
                task = StructureGenerators.createWall(level, wallCenter, direction, wallLen, wallHeight, material, crenellations);
                break;
            }

            case "platform":
            case "floor": {
                int sx = json.has("size_x") ? json.get("size_x").getAsInt() : (json.has("size") ? json.get("size").getAsInt() : 5);
                int sz = json.has("size_z") ? json.get("size_z").getAsInt() : (json.has("size") ? json.get("size").getAsInt() : 5);
                BlockPos platCenter = companionPos.relative(direction, (Math.max(sx, sz) / 2) + 1);
                task = StructureGenerators.createPlatform(level, platCenter, sx, sz, material);
                break;
            }

            default: {
                JarvisHttpServer.sendError(exchange, 400, "Unknown structure type: '" + structureType +
                        "'. Supported structures: bridge, shelter, wall, platform");
                return;
            }
        }

        BuildManager.getInstance().startTask(task);

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.add("task", task.toJson());
        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }
}
