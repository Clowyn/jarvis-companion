package com.alcyone.jarvis.server.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages an automatic, zero-configuration Cloudflare Quick Tunnel (trycloudflare.com).
 * Exposes the Jarvis HTTP Server securely over HTTPS for free without opening router/firewall ports.
 */
public class CloudflareTunnel {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");
    private static final Pattern URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com");

    private static volatile Process tunnelProcess;
    private static volatile String activeUrl = null;
    private static volatile boolean running = false;

    public static String getActiveUrl() {
        return activeUrl;
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Starts the Cloudflare Quick Tunnel in a virtual background thread.
     *
     * @param localPort The local port where Jarvis HTTP Server is listening (typically 25585).
     */
    public static synchronized void start(int localPort) {
        if (running) {
            return;
        }
        running = true;
        Thread.ofVirtual().name("jarvis-cloudflare-tunnel").start(() -> runTunnel(localPort));
    }

    /**
     * Stops the Cloudflare tunnel process if active.
     */
    public static synchronized void stop() {
        running = false;
        activeUrl = null;
        if (tunnelProcess != null) {
            LOGGER.info("[Jarvis] Stopping Cloudflare Tunnel...");
            try {
                tunnelProcess.destroy();
                if (!tunnelProcess.waitFor(5, TimeUnit.SECONDS)) {
                    tunnelProcess.destroyForcibly();
                }
            } catch (Exception e) {
                LOGGER.warn("[Jarvis] Error while stopping Cloudflare tunnel process: {}", e.getMessage());
            } finally {
                tunnelProcess = null;
            }
        }
    }

    private static void runTunnel(int localPort) {
        try {
            Path binary = ensureBinaryExists();
            if (binary == null || !Files.exists(binary)) {
                LOGGER.warn("[Jarvis] Cloudflare tunnel binary could not be prepared. Tunnel will not start.");
                running = false;
                return;
            }

            LOGGER.info("[Jarvis] Starting Cloudflare Quick Tunnel pointing to http://127.0.0.1:{}...", localPort);
            ProcessBuilder pb = new ProcessBuilder(
                    binary.toAbsolutePath().toString(),
                    "tunnel",
                    "--no-autoupdate",
                    "--url", "http://127.0.0.1:" + localPort
            );
            pb.redirectErrorStream(true);

            tunnelProcess = pb.start();

            // Register JVM shutdown hook to clean up tunnel process on server exit
            Runtime.getRuntime().addShutdownHook(new Thread(CloudflareTunnel::stop));

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(tunnelProcess.getInputStream()))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    Matcher matcher = URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        activeUrl = matcher.group();
                        LOGGER.info("================================================================================");
                        LOGGER.info("[Jarvis] [CLOUDFLARE QUICK TUNNEL ESTABLISHED]");
                        LOGGER.info("[Jarvis] Public API URL: {}", activeUrl);
                        LOGGER.info("[Jarvis] Connect MCP bridge via: $env:JARVIS_API_URL=\"{}\"", activeUrl);
                        LOGGER.info("================================================================================");
                    }
                }
            }

            if (tunnelProcess != null) {
                int exitCode = tunnelProcess.waitFor();
                if (running) {
                    LOGGER.warn("[Jarvis] Cloudflare tunnel exited unexpectedly with code {}", exitCode);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Failed to run Cloudflare tunnel: {}", e.getMessage(), e);
        } finally {
            running = false;
        }
    }

    private static Path ensureBinaryExists() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String binaryName;
        String downloadName;

        if (os.contains("win")) {
            binaryName = "cloudflared-windows-amd64.exe";
            downloadName = "cloudflared-windows-amd64.exe";
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                binaryName = "cloudflared-darwin-arm64";
                downloadName = "cloudflared-darwin-arm64";
            } else {
                binaryName = "cloudflared-darwin-amd64";
                downloadName = "cloudflared-darwin-amd64";
            }
        } else {
            // Linux default (e.g. Exaroton Docker containers)
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                binaryName = "cloudflared-linux-arm64";
                downloadName = "cloudflared-linux-arm64";
            } else {
                binaryName = "cloudflared-linux-amd64";
                downloadName = "cloudflared-linux-amd64";
            }
        }

        Path binDir = Paths.get("jarvis", "bin");
        Path binaryPath = binDir.resolve(binaryName);

        try {
            if (Files.exists(binaryPath) && Files.size(binaryPath) > 1000000) {
                // Already downloaded and cached
                ensureExecutable(binaryPath);
                return binaryPath;
            }

            Files.createDirectories(binDir);
            String downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/" + downloadName;
            LOGGER.info("[Jarvis] Downloading Cloudflare Tunnel binary from {}...", downloadUrl);

            Path tempDownload = binDir.resolve(binaryName + ".tmp");
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .GET()
                    .header("User-Agent", "Jarvis-Companion-Mod/1.0.0")
                    .build();

            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempDownload));
            if (response.statusCode() != 200) {
                LOGGER.error("[Jarvis] Failed to download cloudflared: HTTP {}", response.statusCode());
                Files.deleteIfExists(tempDownload);
                return null;
            }

            Files.move(tempDownload, binaryPath, StandardCopyOption.REPLACE_EXISTING);
            ensureExecutable(binaryPath);
            LOGGER.info("[Jarvis] Cloudflare Tunnel binary successfully downloaded to {}", binaryPath);
            return binaryPath;
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error preparing Cloudflare Tunnel binary: {}", e.getMessage(), e);
            return null;
        }
    }

    private static void ensureExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
            path.toFile().setReadable(true, false);
        } catch (Exception ignored) {}
    }
}
