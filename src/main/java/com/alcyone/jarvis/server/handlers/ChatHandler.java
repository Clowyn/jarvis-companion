package com.alcyone.jarvis.server.handlers;

import com.alcyone.jarvis.chat.ChatHistory;
import com.alcyone.jarvis.server.JarvisHttpServer;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Handles GET /api/chat.
 * Returns buffered player chat messages from the circular ChatHistory buffer.
 */
public class ChatHandler implements HttpHandler {
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

        Map<String, String> params = JarvisHttpServer.parseQueryParams(exchange);

        int limit = 50;
        if (params.containsKey("limit")) {
            try {
                int parsed = Integer.parseInt(params.get("limit"));
                limit = Math.max(1, Math.min(parsed, 100));
            } catch (NumberFormatException ignored) {}
        }

        long since = 0L;
        if (params.containsKey("since")) {
            try {
                since = Math.max(0L, Long.parseLong(params.get("since")));
            } catch (NumberFormatException ignored) {}
        }

        String format = params.getOrDefault("format", "object");

        try {
            List<ChatHistory.ChatEntry> entries = ChatHistory.getRecent(limit, since);

            if ("array".equalsIgnoreCase(format)) {
                JarvisHttpServer.sendJsonResponse(exchange, 200, entries);
            } else {
                JsonObject root = new JsonObject();
                root.addProperty("count", entries.size());
                root.add("messages", JarvisHttpServer.getGson().toJsonTree(entries));
                JarvisHttpServer.sendJsonResponse(exchange, 200, root);
            }
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error querying chat history", e);
            JarvisHttpServer.sendError(exchange, 500, "Failed to retrieve chat history: " + e.getMessage());
        }
    }
}
