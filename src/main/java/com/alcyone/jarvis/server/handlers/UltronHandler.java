package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.server.JarvisHttpServer;
import com.alcyone.jarvis.ultron.UltronManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * Handles POST /api/companion/ultron.
 * Triggers the rare Ultron jump-scare transformation event.
 */
public class UltronHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (JarvisHttpServer.handleCorsPreflight(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JarvisHttpServer.sendError(exchange, 405, "Method Not Allowed, use POST");
            return;
        }

        MinecraftServer server = JarvisHttpServer.getServer();
        if (server == null) {
            JarvisHttpServer.sendError(exchange, 503, "Minecraft server is not ready");
            return;
        }

        String quote = null;
        int blindnessSeconds = 15;

        String body = JarvisHttpServer.readBody(exchange);
        if (body != null && !body.isBlank()) {
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("quote")) {
                    quote = json.get("quote").getAsString();
                }
                if (json.has("blindness_seconds")) {
                    blindnessSeconds = json.get("blindness_seconds").getAsInt();
                }
            } catch (Exception ignored) {}
        }

        String executedQuote = UltronManager.triggerUltronEvent(server, quote, blindnessSeconds);

        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("quote", executedQuote);
        resp.addProperty("blindness_seconds", blindnessSeconds);
        JarvisHttpServer.sendJsonResponse(exchange, 200, resp);
    }
}
