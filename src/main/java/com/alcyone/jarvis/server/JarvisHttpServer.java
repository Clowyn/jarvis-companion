package com.alcyone.jarvis.server;

import com.alcyone.jarvis.server.handlers.BuildHandler;
import com.alcyone.jarvis.server.handlers.ChatHandler;
import com.alcyone.jarvis.server.handlers.CommandHandler;
import com.alcyone.jarvis.server.handlers.CompanionHandler;
import com.alcyone.jarvis.server.handlers.ContainerHandler;
import com.alcyone.jarvis.server.handlers.ModpackHandler;
import com.alcyone.jarvis.server.handlers.SayHandler;
import com.alcyone.jarvis.server.handlers.StatusHandler;
import com.alcyone.jarvis.server.handlers.SurroundingsHandler;
import com.alcyone.jarvis.server.handlers.UltronHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server running on port 25585, backed by Java 21 Virtual Threads.
 * Provides REST endpoints for Minecraft status, chat, commands, and perception.
 */
public class JarvisHttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024; // 1 MB

    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    private static volatile HttpServer server;
    private static volatile MinecraftServer minecraftServer;

    /**
     * Starts the embedded HTTP server on the specified port.
     *
     * @param mcServer MinecraftServer instance.
     * @param port     Port number (typically 25585).
     */
    public static synchronized void start(MinecraftServer mcServer, int port) {
        if (server != null) {
            stop();
        }
        minecraftServer = mcServer;

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            // Java 21 Virtual Threads per request
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            // Register Milestone 1 core handlers
            server.createContext("/api/status", new StatusHandler());
            server.createContext("/api/say", new SayHandler());
            server.createContext("/api/chat", new ChatHandler());
            server.createContext("/api/command", new CommandHandler());

            // Register M2 and M3 handlers
            server.createContext("/api/surroundings", new SurroundingsHandler());
            CompanionHandler companionHandler = new CompanionHandler();
            server.createContext("/api/companion", companionHandler);
            server.createContext("/api/companion/retrieve", companionHandler);
            server.createContext("/api/companion/deposit", companionHandler);
            server.createContext("/api/companion/ultron", new UltronHandler());

            BuildHandler buildHandler = new BuildHandler();
            server.createContext("/api/companion/build", buildHandler);
            server.createContext("/api/companion/build/status", buildHandler);
            server.createContext("/api/companion/build/cancel", buildHandler);

            ContainerHandler containerHandler = new ContainerHandler();
            server.createContext("/api/container", containerHandler);
            server.createContext("/api/container/sort", containerHandler);
            server.createContext("/api/container/transfer", containerHandler);
            server.createContext("/api/container/scan", containerHandler);
            server.createContext("/api/container/organize", containerHandler);

            ModpackHandler modpackHandler = new ModpackHandler();
            server.createContext("/api/modpack", modpackHandler);
            server.createContext("/api/modpack/recipes", modpackHandler);
            server.createContext("/api/modpack/machines", modpackHandler);
            server.createContext("/api/modpack/mods", modpackHandler);

            // Fallback 404 handler for unregistered routes
            server.createContext("/", exchange -> {
                if (handleCorsPreflight(exchange)) {
                    return;
                }
                sendError(exchange, 404, "Endpoint not found");
            });

            server.start();
            LOGGER.info("[Jarvis] Embedded HTTP Server started on http://localhost:{}/ with Virtual Threads", port);
        } catch (IOException e) {
            LOGGER.error("[Jarvis] Failed to start HTTP server on port {}", port, e);
        }
    }

    /**
     * Stops the embedded HTTP server and releases bound sockets.
     */
    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop(1);
            } catch (Exception e) {
                LOGGER.warn("[Jarvis] Error while stopping HTTP server", e);
            }
            server = null;
            minecraftServer = null;
            LOGGER.info("[Jarvis] Embedded HTTP Server stopped.");
        }
    }

    /**
     * Returns the active MinecraftServer reference.
     */
    public static MinecraftServer getServer() {
        return minecraftServer;
    }

    /**
     * Returns the shared Gson instance.
     */
    public static Gson getGson() {
        return GSON;
    }

    /**
     * Checks if the request is an OPTIONS CORS preflight and handles it if so.
     *
     * @param exchange The HttpExchange.
     * @return true if handled as OPTIONS preflight, false otherwise.
     * @throws IOException On network error.
     */
    public static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    /**
     * Adds standard CORS headers to the response.
     */
    public static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
    }

    /**
     * Sends a JSON response with status code and CORS headers.
     *
     * @param exchange   The HttpExchange.
     * @param statusCode HTTP response status code.
     * @param body       Object or JsonElement to serialize, or String to write directly.
     * @throws IOException On write error.
     */
    public static void sendJsonResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        String jsonString;
        if (body == null) {
            jsonString = "{}";
        } else if (body instanceof String s) {
            jsonString = s;
        } else if (body instanceof JsonElement je) {
            jsonString = GSON.toJson(je);
        } else {
            jsonString = GSON.toJson(body);
        }

        byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends a standardized JSON error response.
     *
     * @param exchange   The HttpExchange.
     * @param statusCode HTTP response status code.
     * @param message    Error description message.
     * @throws IOException On write error.
     */
    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("success", false);
        errorObj.addProperty("error", message);
        errorObj.addProperty("status", statusCode);
        sendJsonResponse(exchange, statusCode, errorObj);
    }

    /**
     * Reads the request body as a UTF-8 string up to MAX_PAYLOAD_BYTES (1 MB).
     * Throws PayloadTooLargeException if the payload exceeds 1MB.
     *
     * @param exchange The HttpExchange.
     * @return String content of the body.
     * @throws IOException On read error or payload too large.
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null) {
            try {
                long len = Long.parseLong(contentLengthHeader.trim());
                if (len > MAX_PAYLOAD_BYTES) {
                    throw new PayloadTooLargeException("Payload exceeds maximum permitted size of 1MB");
                }
            } catch (NumberFormatException ignored) {}
        }

        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readNBytes(MAX_PAYLOAD_BYTES + 1);
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                throw new PayloadTooLargeException("Payload exceeds maximum permitted size of 1MB");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses query parameters into a key-value Map.
     *
     * @param exchange The HttpExchange.
     * @return Map of decoded query parameters.
     */
    public static Map<String, String> parseQueryParams(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> params = new HashMap<>();
        for (String param : query.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(param.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else if (!param.isEmpty()) {
                String key = URLDecoder.decode(param, StandardCharsets.UTF_8);
                params.put(key, "");
            }
        }
        return params;
    }
}
