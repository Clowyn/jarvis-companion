package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.entity.CompanionManager;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Handles all /api/companion/* REST API endpoints:
 * - GET /api/companion/status
 * - GET /api/companion/inventory
 * - POST /api/companion/spawn
 * - POST /api/companion/despawn
 * - POST /api/companion/action
 */
public class CompanionHandler implements HttpHandler {
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

        if (path.equals("/api/companion/status") || path.equals("/api/companion") || path.equals("/api/companion/")) {
            if (!"GET".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleStatus(exchange, server);
            return;
        }

        if (path.equals("/api/companion/inventory")) {
            if (!"GET".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleInventory(exchange, server);
            return;
        }

        if (path.equals("/api/companion/spawn")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleSpawn(exchange, server);
            return;
        }

        if (path.equals("/api/companion/despawn")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleDespawn(exchange, server);
            return;
        }

        if (path.equals("/api/companion/action")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleAction(exchange, server);
            return;
        }

        if (path.equals("/api/companion/retrieve") || path.equals("/api/companion/retrieve/")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleRetrieve(exchange, server);
            return;
        }

        if (path.equals("/api/companion/deposit") || path.equals("/api/companion/deposit/")) {
            if (!"POST".equalsIgnoreCase(method)) {
                JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            handleDeposit(exchange, server);
            return;
        }

        JarvisHttpServer.sendError(exchange, 404, "Endpoint not found");
    }

    private void handleStatus(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject status = CompanionManager.getInstance().getStatus(server);
        JarvisHttpServer.sendJsonResponse(exchange, 200, status);
    }

    private void handleInventory(HttpExchange exchange, MinecraftServer server) throws IOException {
        CompanionManager.ManagerResult result = CompanionManager.getInstance().getInventory(server);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
    }

    private void handleSpawn(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        CompanionManager.ManagerResult result = CompanionManager.getInstance().spawn(server, body);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
    }

    private void handleDespawn(HttpExchange exchange, MinecraftServer server) throws IOException {
        CompanionManager.ManagerResult result = CompanionManager.getInstance().despawn(server);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
    }

    private void handleAction(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        if (!body.has("action") || body.get("action").isJsonNull()) {
            JarvisHttpServer.sendError(exchange, 400, "Action must be specified");
            return;
        }

        JsonElement actionElem = body.get("action");
        if (!actionElem.isJsonPrimitive() || !actionElem.getAsJsonPrimitive().isString()) {
            JarvisHttpServer.sendError(exchange, 400, "Action must be specified");
            return;
        }

        String action = actionElem.getAsString().trim();
        if (action.isEmpty()) {
            JarvisHttpServer.sendError(exchange, 400, "Action must be specified");
            return;
        }

        if (!CompanionManager.getInstance().isSpawned()) {
            JarvisHttpServer.sendError(exchange, 400, "Companion is not spawned");
            return;
        }

        ValidationResult validation = validateActionParameters(action, body);
        if (!validation.valid()) {
            JarvisHttpServer.sendError(exchange, validation.statusCode(), validation.errorMessage());
            return;
        }

        CompanionManager.ManagerResult result = CompanionManager.getInstance().executeAction(server, action, body);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
    }

    private void handleRetrieve(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        if (!body.has("item") && !body.has("item_id")) {
            JarvisHttpServer.sendError(exchange, 400, "Missing item identifier for retrieve");
            return;
        }

        CompanionManager.ManagerResult result = CompanionManager.getInstance().retrieve(server, body);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
    }

    private void handleDeposit(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonObject body = parseJsonObject(exchange);
        if (body == null) return;

        CompanionManager.ManagerResult result = CompanionManager.getInstance().deposit(server, body);
        JarvisHttpServer.sendJsonResponse(exchange, result.statusCode(), result.response());
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

    private record ValidationResult(boolean valid, int statusCode, String errorMessage) {
        public static ValidationResult ok() {
            return new ValidationResult(true, 200, null);
        }

        public static ValidationResult error(int code, String message) {
            return new ValidationResult(false, code, message);
        }
    }

    private ValidationResult validateActionParameters(String action, JsonObject body) {
        String act = action.toLowerCase();
        switch (act) {
            case "move_to":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for move_to");
                }
                if (!isNumeric(body.get("x")) || !isNumeric(body.get("y")) || !isNumeric(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for move_to");
                }
                return ValidationResult.ok();

            case "follow":
                if (!body.has("player") || body.get("player").isJsonNull() || body.get("player").getAsString().trim().isEmpty()) {
                    return ValidationResult.error(400, "Missing player to follow");
                }
                return ValidationResult.ok();

            case "stop":
                return ValidationResult.ok();

            case "teleport":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for teleport");
                }
                if (!isNumeric(body.get("x")) || !isNumeric(body.get("y")) || !isNumeric(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for teleport");
                }
                return ValidationResult.ok();

            case "break_block":
            case "mine":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for block action");
                }
                if (!isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for block action");
                }
                return ValidationResult.ok();

            case "place_block":
            case "place":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for place_block");
                }
                if (!isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for place_block");
                }
                return ValidationResult.ok();

            case "interact_block":
            case "use":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for interact_block");
                }
                if (!isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for interact_block");
                }
                return ValidationResult.ok();

            case "inspect_container":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for inspect_container");
                }
                if (!isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for inspect_container");
                }
                return ValidationResult.ok();

            case "attack":
                if (!body.has("entity_id") || body.get("entity_id").isJsonNull()) {
                    return ValidationResult.error(400, "Missing entity_id for attack");
                }
                if (!isInteger(body.get("entity_id"))) {
                    return ValidationResult.error(400, "Invalid entity_id for attack");
                }
                return ValidationResult.ok();

            case "sort_container":
                if (!body.has("x") || !body.has("y") || !body.has("z")) {
                    return ValidationResult.error(400, "Missing coordinates for sort_container");
                }
                if (!isInteger(body.get("x")) || !isInteger(body.get("y")) || !isInteger(body.get("z"))) {
                    return ValidationResult.error(400, "Invalid coordinates for sort_container");
                }
                return ValidationResult.ok();

            case "transfer_items":
                boolean hasSource = (body.has("source") && body.get("source").isJsonObject())
                        || (body.has("source_x") && body.has("source_y") && body.has("source_z"));
                boolean hasTarget = (body.has("target") && body.get("target").isJsonObject())
                        || (body.has("target_x") && body.has("target_y") && body.has("target_z"));
                if (!hasSource || !hasTarget) {
                    return ValidationResult.error(400, "Missing source or target coordinates for transfer_items");
                }
                return ValidationResult.ok();

            case "retrieve_item":
                if (!body.has("item") && !body.has("item_id")) {
                    return ValidationResult.error(400, "Missing item identifier for retrieve_item");
                }
                return ValidationResult.ok();

            case "deposit_items":
                return ValidationResult.ok();

            case "give_items":
            case "empty_inventory":
                return ValidationResult.ok();

            case "smelt_ores":
            case "smelt":
            case "collect_smelted":
            case "collect_ingots":
            case "harvest_crops":
            case "harvest":
            case "breed_animals":
            case "breed":
            case "shear_sheep":
            case "shear":
            case "farm_all":
            case "farm":
            case "chop_trees":
            case "chop_wood":
            case "lumberjack":
                return ValidationResult.ok();

            case "craft":
            case "craft_item":
                if (!body.has("item") && !body.has("item_id")) {
                    return ValidationResult.error(400, "Missing item identifier for craft");
                }
                return ValidationResult.ok();

            case "auto_replenish":
            case "replenish_tools":
            case "tech_process_ores":
            case "process_ores_tech":
            case "tech_scan_machines":
            case "scan_machines":
            case "light_up_area":
            case "light_up":
            case "place_torches":
            case "neutralize_spawners":
            case "clear_spawners":
                return ValidationResult.ok();

            default:
                return ValidationResult.error(400, "Unknown action: " + action);
        }
    }

    private static boolean isNumeric(JsonElement elem) {
        if (elem == null || !elem.isJsonPrimitive()) return false;
        try {
            Double.parseDouble(elem.getAsString());
            return true;
        } catch (NumberFormatException e) {
            return false;
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
}
