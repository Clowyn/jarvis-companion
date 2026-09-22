package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.chat.ChatHistory;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles POST /api/say.
 * Safely broadcasts formatted chat messages to in-game players.
 */
public class SayHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");

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

        if (!json.has("message") || json.get("message").isJsonNull()) {
            JarvisHttpServer.sendError(exchange, 400, "Field 'message' is required");
            return;
        }

        if (!json.get("message").isJsonPrimitive() || !json.get("message").getAsJsonPrimitive().isString()) {
            JarvisHttpServer.sendError(exchange, 400, "Field 'message' must be a string");
            return;
        }

        String message = json.get("message").getAsString();
        if (message == null || message.trim().isEmpty()) {
            JarvisHttpServer.sendError(exchange, 400, "Message cannot be empty or whitespace");
            return;
        }

        String sender = "Jarvis";
        if (json.has("sender") && !json.get("sender").isJsonNull()) {
            if (json.get("sender").isJsonPrimitive() && json.get("sender").getAsJsonPrimitive().isString()) {
                String customSender = json.get("sender").getAsString().trim();
                if (!customSender.isEmpty()) {
                    sender = customSender;
                }
            }
        }

        String target = null;
        if (json.has("target") && !json.get("target").isJsonNull()) {
            if (json.get("target").isJsonPrimitive() && json.get("target").getAsJsonPrimitive().isString()) {
                String customTarget = json.get("target").getAsString().trim();
                if (!customTarget.isEmpty()) {
                    target = customTarget;
                }
            }
        }

        final String finalSender = sender;
        final String finalMessage = message;
        final String finalTarget = target;

        try {
            boolean broadcasted = ThreadHelper.supplyOnMain(server, () -> {
                Component formatted = Component.literal("[" + finalSender + "] ")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                        .append(Component.literal(finalMessage).withStyle(ChatFormatting.WHITE));

                if (finalTarget != null) {
                    ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(finalTarget);
                    if (targetPlayer != null) {
                        targetPlayer.sendSystemMessage(formatted);
                        return false; // Whispered, not broadcasted globally
                    }
                }
                server.getPlayerList().broadcastSystemMessage(formatted, false);
                return true;
            }, 3, TimeUnit.SECONDS);

            // Record to ChatHistory
            ChatHistory.add(finalSender, finalMessage);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("broadcasted", broadcasted);
            if (finalTarget != null) {
                response.addProperty("target", finalTarget);
            }
            JarvisHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (TimeoutException e) {
            LOGGER.warn("[Jarvis] Message broadcast timed out on main thread");
            JarvisHttpServer.sendError(exchange, 504, "Server tick timed out broadcasting message");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error broadcasting message", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to broadcast message: " + e.getMessage());
        }
    }
}
