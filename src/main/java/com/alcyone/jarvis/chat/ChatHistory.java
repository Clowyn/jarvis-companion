package com.alcyone.jarvis.chat;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, lock-free circular buffer for in-game chat messages.
 * Uses AtomicInteger size tracking to guarantee O(1) bound enforcement.
 */
public class ChatHistory {
    public static final int MAX_ENTRIES = 100;

    public static class ChatEntry {
        @SerializedName("id")
        public final long id;

        @SerializedName("player")
        public final String player;

        @SerializedName("message")
        public final String message;

        @SerializedName("timestamp")
        public final long timestamp;

        @SerializedName("is_command")
        public final boolean isCommand;

        @SerializedName("is_trigger")
        public final boolean isTrigger;

        public ChatEntry(long id, String player, String message, long timestamp, boolean isCommand, boolean isTrigger) {
            this.id = id;
            this.player = player;
            this.message = message;
            this.timestamp = timestamp;
            this.isCommand = isCommand;
            this.isTrigger = isTrigger;
        }

        public long getId() {
            return id;
        }

        public String getPlayer() {
            return player;
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isCommand() {
            return isCommand;
        }

        public boolean isTrigger() {
            return isTrigger;
        }
    }

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);
    private static final ConcurrentLinkedDeque<ChatEntry> ENTRIES = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger SIZE = new AtomicInteger(0);

    /**
     * Appends a chat message to the circular history buffer.
     * Enforces MAX_ENTRIES bound with O(1) complexity.
     *
     * @param player  The player username or sender.
     * @param message The raw chat text.
     * @return The created ChatEntry.
     */
    public static ChatEntry add(String player, String message) {
        long id = ID_GENERATOR.getAndIncrement();
        long timestamp = System.currentTimeMillis();
        String safePlayer = (player != null) ? player : "Unknown";
        String safeMessage = (message != null) ? message : "";
        boolean isCommand = safeMessage.startsWith("!jarvis") || safeMessage.startsWith("@jarvis")
                || safeMessage.startsWith("/") || safeMessage.startsWith("!");
        boolean isTrigger = safeMessage.startsWith("!jarvis") || safeMessage.startsWith("@jarvis");

        ChatEntry entry = new ChatEntry(id, safePlayer, safeMessage, timestamp, isCommand, isTrigger);
        ENTRIES.addLast(entry);

        if (SIZE.incrementAndGet() > MAX_ENTRIES) {
            while (SIZE.get() > MAX_ENTRIES) {
                if (ENTRIES.pollFirst() != null) {
                    SIZE.decrementAndGet();
                } else {
                    break;
                }
            }
        }
        return entry;
    }

    /**
     * Retrieves recent chat entries matching the since timestamp, capped by limit.
     *
     * @param limit Maximum number of entries to return (1 to 100).
     * @param since Timestamp floor in milliseconds (exclusive).
     * @return List of matching ChatEntry items in chronological order.
     */
    public static List<ChatEntry> getRecent(int limit, long since) {
        int maxLimit = Math.max(1, Math.min(limit, MAX_ENTRIES));
        List<ChatEntry> matched = new ArrayList<>();

        for (ChatEntry entry : ENTRIES) {
            boolean matches;
            if (since <= 0) {
                matches = true;
            } else if (since < 100_000_000_000L) {
                // Treated as a chat entry sequential ID
                matches = entry.id > since;
            } else {
                // Treated as a millisecond Unix timestamp
                matches = entry.timestamp > since;
            }
            if (matches) {
                matched.add(entry);
            }
        }

        if (matched.size() <= maxLimit) {
            return matched;
        }

        // If since was 0 (initial request), return the latest 'limit' messages
        if (since <= 0) {
            return new ArrayList<>(matched.subList(matched.size() - maxLimit, matched.size()));
        } else {
            // For polling stream forward, return the earliest 'limit' messages after 'since'
            return new ArrayList<>(matched.subList(0, maxLimit));
        }
    }

    /**
     * Overload for backwards compatibility.
     *
     * @param since Timestamp floor in milliseconds (exclusive).
     * @return List of matching entries (default limit 50).
     */
    public static List<ChatEntry> getRecent(long since) {
        return getRecent(50, since);
    }

    /**
     * Returns current number of entries in the buffer.
     *
     * @return Size count.
     */
    public static int size() {
        return SIZE.get();
    }

    /**
     * Clears all entries from the buffer (resets size to 0).
     */
    public static void clear() {
        ENTRIES.clear();
        SIZE.set(0);
    }
}
