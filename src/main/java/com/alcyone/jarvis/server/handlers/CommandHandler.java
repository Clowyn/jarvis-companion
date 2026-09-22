package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles POST /api/command.
 * Safely executes in-game server commands on the main tick thread with permission level 4 (Console/Op)
 * and captures feedback output lines and exit code.
 */
public class CommandHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

    private record CommandResult(int exitCode, List<String> outputLines) {}

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (JarvisHttpServer.handleCorsPreflight(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JarvisHttpServer.sendError(exchange, 405, "Method not allowed, use POST");
            return;
        }

        MinecraftServer server = JarvisHttpServer.getServer();
        if (server == null) {
            JarvisHttpServer.sendError(exchange, 503, "Minecraft server is not ready or offline");
            return;
        }

        String body;
        try {
            body = JarvisHttpServer.readBody(exchange);
        } catch (JarvisHttpServer.PayloadTooLargeException e) {
            JarvisHttpServer.sendError(exchange, 413, "Payload too large: exceeds 1MB");
            return;
        }

        if (body == null || body.trim().isEmpty()) {
            JarvisHttpServer.sendError(exchange, 400, "Request body cannot be empty");
            return;
        }

        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                JarvisHttpServer.sendError(exchange, 400, "Malformed JSON: expected JSON object");
                return;
            }
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            JarvisHttpServer.sendError(exchange, 400, "Malformed JSON syntax");
            return;
        }

        if (!json.has("command") || json.get("command").isJsonNull()) {
            JarvisHttpServer.sendError(exchange, 400, "Field 'command' is required");
            return;
        }

        if (!json.get("command").isJsonPrimitive() || !json.get("command").getAsJsonPrimitive().isString()) {
            JarvisHttpServer.sendError(exchange, 400, "Field 'command' must be a string");
            return;
        }

        String rawCommand = json.get("command").getAsString();
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            JarvisHttpServer.sendError(exchange, 400, "Command cannot be empty or whitespace");
            return;
        }

        // Remove leading slash if provided
        final String commandToExecute = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;

        try {
            CommandResult result = ThreadHelper.supplyOnMain(server, () -> {
                List<String> outputLines = new ArrayList<>();
                CommandSource captureSource = new CommandSource() {
                    @Override
                    public void sendSystemMessage(Component message) {
                        outputLines.add(message.getString());
                    }

                    @Override
                    public boolean acceptsSuccess() {
                        return true;
                    }

                    @Override
                    public boolean acceptsFailure() {
                        return true;
                    }

                    @Override
                    public boolean shouldInformAdmins() {
                        return false;
                    }
                };

                ServerLevel overworld = server.overworld();
                Vec3 spawnPos = overworld != null ? Vec3.atLowerCornerOf(overworld.getSharedSpawnPos()) : Vec3.ZERO;

                CommandSourceStack stack = new CommandSourceStack(
                        captureSource,
                        spawnPos,
                        Vec2.ZERO,
                        overworld,
                        4, // Permission level 4 (Console / Op)
                        "Jarvis",
                        Component.literal("Jarvis"),
                        server,
                        null
                );

                server.getCommands().performPrefixedCommand(stack, commandToExecute);
                return new CommandResult(1, outputLines);
            }, 5, TimeUnit.SECONDS);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("exit_code", result.exitCode());

            JsonArray outputArray = new JsonArray();
            for (String line : result.outputLines()) {
                outputArray.add(line);
            }
            response.add("output", outputArray);

            JarvisHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (TimeoutException e) {
            LOGGER.warn("[Jarvis] Command execution timed out on main thread: {}", commandToExecute);
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", "Command execution timed out on main server thread");
            JarvisHttpServer.sendJsonResponse(exchange, 504, err);
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error executing command: {}", commandToExecute, e);
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", "Failed to execute command: " + e.getMessage());
            JarvisHttpServer.sendJsonResponse(exchange, 500, err);
        }
    }
}
