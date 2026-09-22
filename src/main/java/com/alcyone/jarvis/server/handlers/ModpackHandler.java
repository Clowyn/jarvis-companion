package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.entity.CompanionManager;
import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import com.alcyone.jarvis.modpack.MachineScanner;
import com.alcyone.jarvis.modpack.RecipeSearcher;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles modpack awareness, in-game recipes, mod lists, and machine perception:
 * - GET /api/modpack/recipes?item=<item_name>
 * - GET /api/modpack/machines?radius=16
 * - GET /api/modpack/mods
 */
public class ModpackHandler implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (JarvisHttpServer.handleCorsPreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed, use GET");
            return;
        }

        MinecraftServer server = JarvisHttpServer.getServer();
        if (server == null) {
            JarvisHttpServer.sendError(exchange, 503, "Minecraft server is not ready");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());

        if (path.endsWith("/recipes")) {
            handleRecipes(exchange, server, params);
            return;
        }

        if (path.endsWith("/machines")) {
            handleMachines(exchange, server, params);
            return;
        }

        if (path.endsWith("/mods")) {
            handleMods(exchange);
            return;
        }

        JarvisHttpServer.sendError(exchange, 404, "Unknown modpack endpoint: " + path);
    }

    private void handleRecipes(HttpExchange exchange, MinecraftServer server, Map<String, String> params) throws IOException {
        String query = params.getOrDefault("item", params.getOrDefault("q", params.getOrDefault("query", "")));
        if (query.isBlank()) {
            JarvisHttpServer.sendError(exchange, 400, "Missing required query parameter 'item'");
            return;
        }

        int limit = 15;
        if (params.containsKey("limit")) {
            try {
                limit = Integer.parseInt(params.get("limit"));
            } catch (NumberFormatException ignored) {}
        }

        JsonArray recipes = RecipeSearcher.searchRecipes(server, query, limit);
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("query", query);
        resp.addProperty("count", recipes.size());
        resp.add("recipes", recipes);

        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }

    private void handleMachines(HttpExchange exchange, MinecraftServer server, Map<String, String> params) throws IOException {
        int radius = 16;
        if (params.containsKey("radius")) {
            try {
                radius = Integer.parseInt(params.get("radius"));
            } catch (NumberFormatException ignored) {}
        }

        JarvisCompanionEntity companion = CompanionManager.getInstance().getActiveCompanion();
        ServerLevel level;
        BlockPos origin;

        if (companion != null && companion.isAlive()) {
            level = (ServerLevel) companion.level();
            origin = companion.blockPosition();
        } else {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                JarvisHttpServer.sendError(exchange, 400, "No active companion or player in the world to center machine scan");
                return;
            }
            ServerPlayer first = players.get(0);
            level = first.serverLevel();
            origin = first.blockPosition();
        }

        JsonArray machines = MachineScanner.scanMachines(level, origin, radius);
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("radius", radius);
        resp.addProperty("count", machines.size());
        resp.add("machines", machines);

        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }

    private void handleMods(HttpExchange exchange) throws IOException {
        JsonArray modArray = new JsonArray();
        try {
            var mods = ModList.get().getMods();
            for (var mod : mods) {
                JsonObject mObj = new JsonObject();
                mObj.addProperty("id", mod.getModId());
                mObj.addProperty("name", mod.getDisplayName());
                mObj.addProperty("version", mod.getVersion().toString());
                modArray.add(mObj);
            }
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error listing loaded mods", e);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("total_mods", modArray.size());
        resp.add("mods", modArray);

        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                map.put(
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                );
            } else if (pair.length == 1) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }
}
