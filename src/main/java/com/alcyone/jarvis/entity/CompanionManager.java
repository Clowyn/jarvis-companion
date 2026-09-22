package com.alcyone.jarvis.entity;

import com.alcyone.jarvis.util.ThreadHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton manager coordinating companion lifecycle, state tracking,
 * and thread-safe action execution between HTTP virtual threads and Minecraft's main tick loop.
 */
public class CompanionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");
    private static final CompanionManager INSTANCE = new CompanionManager();

    public static CompanionManager getInstance() {
        return INSTANCE;
    }

    private final ReentrantLock lock = new ReentrantLock();

    // Main-thread active companion entity
    private JarvisCompanionEntity activeCompanion = null;

    // Fast-path atomic/volatile snapshot state for HTTP threads
    private volatile boolean isSpawned = false;
    private volatile String companionId = null;
    private volatile String companionName = "Jarvis";
    private volatile String dimension = "minecraft:overworld";
    private volatile String state = "unspawned";
    private volatile String currentAction = null;
    private volatile double posX = 0.0;
    private volatile double posY = 0.0;
    private volatile double posZ = 0.0;
    private volatile float health = 0.0f;
    private volatile float maxHealth = 100.0f;
    private volatile JsonObject targetJson = null;

    public record ManagerResult(int statusCode, JsonObject response) {
        public static ManagerResult ok(JsonObject resp) {
            return new ManagerResult(200, resp);
        }

        public static ManagerResult error(int code, String message) {
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", message);
            err.addProperty("message", message);
            return new ManagerResult(code, err);
        }
    }

    public boolean isSpawned() {
        return isSpawned;
    }

    public JarvisCompanionEntity getActiveCompanion() {
        return activeCompanion;
    }

    /**
     * Resets companion state and discards the active entity on server stop or mod unload.
     */
    public void reset() {
        lock.lock();
        try {
            if (activeCompanion != null && activeCompanion.isAlive()) {
                activeCompanion.discard();
            }
            activeCompanion = null;
            isSpawned = false;
            companionId = null;
            companionName = "Jarvis";
            dimension = "minecraft:overworld";
            state = "unspawned";
            currentAction = null;
            posX = 0.0;
            posY = 0.0;
            posZ = 0.0;
            health = 0.0f;
            maxHealth = 100.0f;
            targetJson = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called when the companion entity is removed or killed in-game.
     */
    public void onEntityRemoved(JarvisCompanionEntity entity) {
        lock.lock();
        try {
            if (this.activeCompanion == entity) {
                this.activeCompanion = null;
                this.isSpawned = false;
                this.companionId = null;
                this.state = "unspawned";
                this.currentAction = null;
                this.targetJson = null;
                this.health = 0.0f;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Spawns or repositions the companion on the main server thread.
     */
    public ManagerResult spawn(MinecraftServer server, JsonObject body) {
        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    // If companion is already active, cleanly discard old instance to reposition
                    if (activeCompanion != null && activeCompanion.isAlive()) {
                        activeCompanion.discard();
                        activeCompanion = null;
                    }

                    String name = "Jarvis";
                    if (body.has("name") && !body.get("name").isJsonNull()) {
                        name = body.get("name").getAsString().trim();
                        if (name.isEmpty()) name = "Jarvis";
                    }

                    String dim = "minecraft:overworld";
                    if (body.has("dimension") && !body.get("dimension").isJsonNull()) {
                        dim = body.get("dimension").getAsString().trim();
                    }

                    ServerLevel level = resolveLevel(server, dim);
                    if (level == null) {
                        level = server.overworld();
                        dim = "minecraft:overworld";
                    }

                    double sx;
                    double sy;
                    double sz;

                    if (body.has("x") && body.has("y") && body.has("z") &&
                            !body.get("x").isJsonNull() && !body.get("y").isJsonNull() && !body.get("z").isJsonNull()) {
                        sx = body.get("x").getAsDouble();
                        sy = body.get("y").getAsDouble();
                        sz = body.get("z").getAsDouble();
                    } else if (body.has("player") && !body.get("player").isJsonNull() && !body.get("player").getAsString().trim().isEmpty()) {
                        String targetPlayer = body.get("player").getAsString().trim();
                        ServerPlayer player = server.getPlayerList().getPlayerByName(targetPlayer);
                        if (player != null) {
                            level = player.serverLevel();
                            dim = level.dimension().location().toString();
                            sx = player.getX() + 0.5;
                            sy = player.getY();
                            sz = player.getZ() + 0.5;
                        } else if (!server.getPlayerList().getPlayers().isEmpty()) {
                            ServerPlayer firstPlayer = server.getPlayerList().getPlayers().get(0);
                            level = firstPlayer.serverLevel();
                            dim = level.dimension().location().toString();
                            sx = firstPlayer.getX() + 0.5;
                            sy = firstPlayer.getY();
                            sz = firstPlayer.getZ() + 0.5;
                        } else {
                            BlockPos spawnPos = level.getSharedSpawnPos();
                            sx = spawnPos.getX() + 0.5;
                            sy = spawnPos.getY();
                            sz = spawnPos.getZ() + 0.5;
                        }
                    } else {
                        if (!server.getPlayerList().getPlayers().isEmpty()) {
                            ServerPlayer firstPlayer = server.getPlayerList().getPlayers().get(0);
                            level = firstPlayer.serverLevel();
                            dim = level.dimension().location().toString();
                            sx = firstPlayer.getX() + 0.5;
                            sy = firstPlayer.getY();
                            sz = firstPlayer.getZ() + 0.5;
                        } else {
                            BlockPos spawnPos = level.getSharedSpawnPos();
                            sx = spawnPos.getX() + 0.5;
                            sy = spawnPos.getY();
                            sz = spawnPos.getZ() + 0.5;
                        }
                    }

                    JarvisCompanionEntity companion = ModEntities.JARVIS_COMPANION.get().create(level);
                    if (companion == null) {
                        return ManagerResult.error(500, "Failed to instantiate companion entity");
                    }

                    companion.moveTo(sx, sy, sz, 0.0F, 0.0F);
                    companion.setCustomName(Component.literal(name));
                    companion.setCustomNameVisible(true);
                    companion.setPersistenceRequired();

                    // Initialize tactical equipment if slots are empty
                    if (companion.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
                        companion.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    }
                    if (companion.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
                        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                    }

                    level.addFreshEntity(companion);

                    this.activeCompanion = companion;
                    this.isSpawned = true;
                    this.companionId = "jarvis-1";
                    this.companionName = name;
                    this.dimension = dim;
                    this.state = "idle";
                    this.currentAction = null;
                    this.targetJson = null;
                    this.posX = sx;
                    this.posY = sy;
                    this.posZ = sz;
                    this.health = companion.getHealth();
                    this.maxHealth = companion.getMaxHealth();

                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    res.addProperty("companion_id", this.companionId);
                    JsonObject pos = new JsonObject();
                    pos.addProperty("x", sx);
                    pos.addProperty("y", sy);
                    pos.addProperty("z", sz);
                    res.add("position", pos);

                    return ManagerResult.ok(res);
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out during spawn");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error spawning companion", e);
            return ManagerResult.error(500, "Failed to spawn companion: " + e.getMessage());
        }
    }

    /**
     * Queries current status. Fast-path when unspawned, main-thread query when active.
     */
    public JsonObject getStatus(MinecraftServer server) {
        if (!this.isSpawned) {
            return buildUnspawnedStatus();
        }

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        this.isSpawned = false;
                        this.activeCompanion = null;
                        this.state = "unspawned";
                        return buildUnspawnedStatus();
                    }

                    this.posX = activeCompanion.getX();
                    this.posY = activeCompanion.getY();
                    this.posZ = activeCompanion.getZ();
                    this.health = activeCompanion.getHealth();
                    this.maxHealth = activeCompanion.getMaxHealth();
                    this.state = activeCompanion.getCompanionState().toString().toLowerCase();
                    this.targetJson = activeCompanion.getTargetJson();

                    JsonObject status = new JsonObject();
                    status.addProperty("spawned", true);
                    status.addProperty("active", true);
                    status.addProperty("companion_id", this.companionId != null ? this.companionId : "jarvis-1");
                    status.addProperty("name", this.companionName);
                    status.addProperty("dimension", activeCompanion.level().dimension().location().toString());

                    JsonObject pos = new JsonObject();
                    pos.addProperty("x", this.posX);
                    pos.addProperty("y", this.posY);
                    pos.addProperty("z", this.posZ);
                    status.add("position", pos);

                    status.addProperty("x", this.posX);
                    status.addProperty("y", this.posY);
                    status.addProperty("z", this.posZ);

                    status.addProperty("health", (double) this.health);
                    status.addProperty("max_health", (double) this.maxHealth);
                    status.addProperty("state", this.state);
                    status.add("current_action", this.currentAction != null ? new com.google.gson.JsonPrimitive(this.currentAction) : null);
                    status.add("target", this.targetJson);

                    if (this.targetJson != null && this.targetJson.has("x")) {
                        status.addProperty("target_x", this.targetJson.get("x").getAsDouble());
                        status.addProperty("target_y", this.targetJson.get("y").getAsDouble());
                        status.addProperty("target_z", this.targetJson.get("z").getAsDouble());
                    } else {
                        status.add("target_x", null);
                        status.add("target_y", null);
                        status.add("target_z", null);
                    }

                    return status;
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[Jarvis] Falling back to cached status snapshot", e);
            return buildUnspawnedStatus();
        }
    }

    private JsonObject buildUnspawnedStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("spawned", false);
        status.addProperty("active", false);
        status.add("companion_id", null);
        status.add("name", null);
        status.add("dimension", null);
        status.add("position", null);
        status.add("x", null);
        status.add("y", null);
        status.add("z", null);
        status.addProperty("health", 0.0);
        status.addProperty("max_health", (double) this.maxHealth);
        status.addProperty("state", "unspawned");
        status.add("current_action", null);
        status.add("target", null);
        status.add("target_x", null);
        status.add("target_y", null);
        status.add("target_z", null);
        return status;
    }

    /**
     * Despawns active companion on main thread.
     */
    public ManagerResult despawn(MinecraftServer server) {
        if (!this.isSpawned) {
            return ManagerResult.error(400, "Companion not spawned");
        }

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        reset();
                        return ManagerResult.error(400, "Companion not spawned");
                    }

                    activeCompanion.discard();
                    reset();

                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    res.addProperty("message", "Companion despawned");
                    return ManagerResult.ok(res);
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out during despawn");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error despawning companion", e);
            return ManagerResult.error(500, "Failed to despawn companion: " + e.getMessage());
        }
    }

    /**
     * Executes companion actions on main thread.
     */
    public ManagerResult executeAction(MinecraftServer server, String rawAction, JsonObject body) {
        if (!this.isSpawned) {
            return ManagerResult.error(400, "Companion is not spawned");
        }

        String action = rawAction.trim().toLowerCase();

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        reset();
                        return ManagerResult.error(400, "Companion is not spawned");
                    }

                    this.currentAction = action;

                    switch (action) {
                        case "move_to": {
                            double tx = body.get("x").getAsDouble();
                            double ty = body.get("y").getAsDouble();
                            double tz = body.get("z").getAsDouble();
                            double speed = body.has("speed") ? body.get("speed").getAsDouble() : 1.0;

                            activeCompanion.setMoveTarget(new BlockPos((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz)), speed);
                            activeCompanion.setCompanionState(JarvisCompanionEntity.State.NAVIGATING);

                            this.posX = activeCompanion.getX();
                            this.posY = activeCompanion.getY();
                            this.posZ = activeCompanion.getZ();
                            this.state = "navigating";

                            JsonObject targetObj = new JsonObject();
                            targetObj.addProperty("x", tx);
                            targetObj.addProperty("y", ty);
                            targetObj.addProperty("z", tz);
                            this.targetJson = targetObj;

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "move_to");
                            res.addProperty("status", "executed");

                            JsonObject details = new JsonObject();
                            details.add("target", targetObj);
                            details.addProperty("speed", speed);
                            res.add("details", details);

                            return ManagerResult.ok(res);
                        }

                        case "follow": {
                            String playerName = body.get("player").getAsString();
                            double dist = body.has("distance") ? body.get("distance").getAsDouble() : 3.0;

                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player == null) {
                                return ManagerResult.error(404, "Player '" + playerName + "' not found");
                            }
                            activeCompanion.setFollowTarget(player, dist);
                            activeCompanion.setCompanionState(JarvisCompanionEntity.State.FOLLOWING);
                            this.state = "following";

                            JsonObject targetObj = new JsonObject();
                            targetObj.addProperty("player", playerName);
                            targetObj.addProperty("distance", dist);
                            this.targetJson = targetObj;

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "follow");
                            res.addProperty("status", "executed");

                            JsonObject details = new JsonObject();
                            details.addProperty("player", playerName);
                            details.addProperty("distance", dist);
                            res.add("details", details);

                            return ManagerResult.ok(res);
                        }

                        case "stop": {
                            activeCompanion.stopAllMovement();
                            this.state = "idle";
                            this.targetJson = null;

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "stop");
                            res.addProperty("status", "executed");
                            res.add("details", new JsonObject());

                            return ManagerResult.ok(res);
                        }

                        case "teleport": {
                            double tx = body.get("x").getAsDouble();
                            double ty = body.get("y").getAsDouble();
                            double tz = body.get("z").getAsDouble();

                            activeCompanion.teleportTo(tx, ty, tz);
                            activeCompanion.stopAllMovement();

                            this.posX = tx;
                            this.posY = ty;
                            this.posZ = tz;
                            this.state = "idle";
                            this.targetJson = null;

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "teleport");
                            res.addProperty("status", "executed");

                            JsonObject details = new JsonObject();
                            JsonObject posObj = new JsonObject();
                            posObj.addProperty("x", tx);
                            posObj.addProperty("y", ty);
                            posObj.addProperty("z", tz);
                            details.add("position", posObj);
                            res.add("details", details);

                            return ManagerResult.ok(res);
                        }

                        case "break_block":
                        case "mine": {
                            int bx = body.get("x").getAsInt();
                            int by = body.get("y").getAsInt();
                            int bz = body.get("z").getAsInt();

                            JarvisFakePlayer.ExecutionResult execRes =
                                    JarvisFakePlayer.executeBreakBlock(activeCompanion, new BlockPos(bx, by, bz));
                            if (!execRes.success()) {
                                return ManagerResult.error(execRes.statusCode(), execRes.errorMessage());
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "break_block");
                            res.addProperty("status", "executed");
                            res.add("details", execRes.details());

                            return ManagerResult.ok(res);
                        }

                        case "place_block":
                        case "place": {
                            int bx = body.get("x").getAsInt();
                            int by = body.get("y").getAsInt();
                            int bz = body.get("z").getAsInt();
                            String item = body.has("item") ? body.get("item").getAsString() :
                                    body.has("block_type") ? body.get("block_type").getAsString() : "minecraft:cobblestone";

                            JarvisFakePlayer.ExecutionResult execRes =
                                    JarvisFakePlayer.executePlaceBlock(activeCompanion, new BlockPos(bx, by, bz), item);
                            if (!execRes.success()) {
                                return ManagerResult.error(execRes.statusCode(), execRes.errorMessage());
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "place_block");
                            res.addProperty("status", "executed");
                            res.add("details", execRes.details());

                            return ManagerResult.ok(res);
                        }

                        case "interact_block":
                        case "use": {
                            int bx = body.get("x").getAsInt();
                            int by = body.get("y").getAsInt();
                            int bz = body.get("z").getAsInt();

                            JarvisFakePlayer.ExecutionResult execRes =
                                    JarvisFakePlayer.executeInteractBlock(activeCompanion, new BlockPos(bx, by, bz));
                            if (!execRes.success()) {
                                return ManagerResult.error(execRes.statusCode(), execRes.errorMessage());
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "interact_block");
                            res.addProperty("status", "executed");
                            res.add("details", execRes.details());

                            return ManagerResult.ok(res);
                        }

                        case "inspect_container": {
                            int bx = body.get("x").getAsInt();
                            int by = body.get("y").getAsInt();
                            int bz = body.get("z").getAsInt();

                            JarvisFakePlayer.ExecutionResult execRes =
                                    JarvisFakePlayer.executeInspectContainer((ServerLevel) activeCompanion.level(), new BlockPos(bx, by, bz));
                            if (!execRes.success()) {
                                return ManagerResult.error(execRes.statusCode(), execRes.errorMessage());
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "inspect_container");
                            res.addProperty("status", "executed");
                            res.add("details", execRes.details());

                            return ManagerResult.ok(res);
                        }

                        case "attack": {
                            int entityId = body.get("entity_id").getAsInt();

                            JarvisFakePlayer.ExecutionResult execRes =
                                    JarvisFakePlayer.executeAttack(activeCompanion, entityId);
                            if (!execRes.success()) {
                                return ManagerResult.error(execRes.statusCode(), execRes.errorMessage());
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "attack");
                            res.addProperty("status", "executed");
                            res.add("details", execRes.details());

                            return ManagerResult.ok(res);
                        }

                        case "sort_container": {
                            int bx = body.get("x").getAsInt();
                            int by = body.get("y").getAsInt();
                            int bz = body.get("z").getAsInt();

                            JsonObject sortRes = com.alcyone.jarvis.storage.StorageManager.sortContainer(
                                    (ServerLevel) activeCompanion.level(), new BlockPos(bx, by, bz));
                            boolean success = sortRes.has("success") && sortRes.get("success").getAsBoolean();
                            if (!success) {
                                String err = sortRes.has("error") ? sortRes.get("error").getAsString() : "Failed to sort container";
                                return ManagerResult.error(400, err);
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "sort_container");
                            res.addProperty("status", "executed");
                            res.add("details", sortRes);

                            return ManagerResult.ok(res);
                        }

                        case "transfer_items": {
                            BlockPos sourcePos = extractBlockPos(body, "source");
                            BlockPos targetPos = extractBlockPos(body, "target");
                            if (sourcePos == null || targetPos == null) {
                                return ManagerResult.error(400, "Missing source or target coordinates");
                            }

                            String filter = body.has("filter") && !body.get("filter").isJsonNull()
                                    ? body.get("filter").getAsString() : "all";

                            JsonObject transferRes = com.alcyone.jarvis.storage.StorageManager.transferItems(
                                    (ServerLevel) activeCompanion.level(), sourcePos, targetPos, filter);
                            boolean success = transferRes.has("success") && transferRes.get("success").getAsBoolean();
                            if (!success) {
                                String err = transferRes.has("error") ? transferRes.get("error").getAsString() : "Failed to transfer items";
                                return ManagerResult.error(400, err);
                            }

                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "transfer_items");
                            res.addProperty("status", "executed");
                            res.add("details", transferRes);

                            return ManagerResult.ok(res);
                        }

                        case "retrieve_item": {
                            String item = body.has("item") ? body.get("item").getAsString()
                                    : body.has("item_id") ? body.get("item_id").getAsString() : null;
                            if (item == null || item.isBlank()) {
                                return ManagerResult.error(400, "Missing item identifier");
                            }
                            int count = body.has("count") && !body.get("count").isJsonNull() ? body.get("count").getAsInt() : 1;

                            ServerPlayer player = resolvePlayer(server, body);
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject retRes = com.alcyone.jarvis.storage.StorageManager.retrieveItem(
                                    (ServerLevel) activeCompanion.level(), searchCenter, activeCompanion, player, item, count);

                            JsonObject res = new JsonObject();
                            boolean success = retRes.has("success") && retRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "retrieve_item");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", retRes);

                            return ManagerResult.ok(res);
                        }

                        case "deposit_items": {
                            String category = body.has("category") && !body.get("category").isJsonNull()
                                    ? body.get("category").getAsString()
                                    : body.has("filter") && !body.get("filter").isJsonNull()
                                    ? body.get("filter").getAsString() : "all";
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 32;

                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject depRes = com.alcyone.jarvis.storage.StorageManager.depositLoot(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, category);

                            JsonObject res = new JsonObject();
                            boolean success = depRes.has("success") && depRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "deposit_items");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", depRes);

                            return ManagerResult.ok(res);
                        }

                        case "give_items":
                        case "empty_inventory": {
                            ServerPlayer player = resolvePlayer(server, body);
                            if (player == null) {
                                return ManagerResult.error(400, "No player found to deliver items to");
                            }

                            List<ItemStack> delivered = activeCompanion.deliverInventoryToPlayer(player);
                            JsonObject res = new JsonObject();
                            res.addProperty("success", true);
                            res.addProperty("action", "give_items");
                            res.addProperty("status", "executed");
                            res.addProperty("player", player.getScoreboardName());
                            res.addProperty("items_count", delivered.size());
                            JsonArray itemsArr = new JsonArray();
                            for (ItemStack s : delivered) {
                                JsonObject sObj = new JsonObject();
                                sObj.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                                sObj.addProperty("count", s.getCount());
                                sObj.addProperty("name", s.getHoverName().getString());
                                itemsArr.add(sObj);
                            }
                            res.add("delivered_items", itemsArr);
                            return ManagerResult.ok(res);
                        }

                        case "smelt_ores":
                        case "smelt": {
                            String filter = body.has("filter") && !body.get("filter").isJsonNull()
                                    ? body.get("filter").getAsString() : "all";
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 24;

                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject smeltRes = com.alcyone.jarvis.smelting.SmeltingManager.operateFurnaces(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, filter);

                            JsonObject res = new JsonObject();
                            boolean success = smeltRes.has("success") && smeltRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "smelt_ores");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", smeltRes);

                            return ManagerResult.ok(res);
                        }

                        case "collect_smelted":
                        case "collect_ingots": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 24;

                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject collRes = com.alcyone.jarvis.smelting.SmeltingManager.collectFurnaceOutputs(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = collRes.has("success") && collRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "collect_smelted");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", collRes);

                            return ManagerResult.ok(res);
                        }

                        case "harvest_crops":
                        case "harvest": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 16;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject farmRes = com.alcyone.jarvis.farming.FarmingManager.harvestAndReplant(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = farmRes.has("success") && farmRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "harvest_crops");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", farmRes);

                            return ManagerResult.ok(res);
                        }

                        case "breed_animals":
                        case "breed": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 16;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject breedRes = com.alcyone.jarvis.farming.FarmingManager.breedAnimals(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = breedRes.has("success") && breedRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "breed_animals");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", breedRes);

                            return ManagerResult.ok(res);
                        }

                        case "shear_sheep":
                        case "shear": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 16;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject shearRes = com.alcyone.jarvis.farming.FarmingManager.shearSheep(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = shearRes.has("success") && shearRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "shear_sheep");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", shearRes);

                            return ManagerResult.ok(res);
                        }

                        case "farm_all":
                        case "farm": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 16;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject farmAllRes = com.alcyone.jarvis.farming.FarmingManager.farmAll(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = farmAllRes.has("success") && farmAllRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "farm_all");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", farmAllRes);

                            return ManagerResult.ok(res);
                        }

                        case "chop_trees":
                        case "chop_wood":
                        case "lumberjack": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 16;
                            int maxTrees = body.has("max_trees") && !body.get("max_trees").isJsonNull() ? body.get("max_trees").getAsInt() : 5;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject chopRes = com.alcyone.jarvis.lumberjack.LumberjackManager.chopTrees(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, maxTrees);

                            JsonObject res = new JsonObject();
                            boolean success = chopRes.has("success") && chopRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "chop_trees");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", chopRes);

                            return ManagerResult.ok(res);
                        }

                        case "craft":
                        case "craft_item": {
                            String item = body.has("item") ? body.get("item").getAsString()
                                    : body.has("item_id") ? body.get("item_id").getAsString() : null;
                            if (item == null || item.isBlank()) {
                                return ManagerResult.error(400, "Missing item identifier for craft");
                            }
                            int count = body.has("count") && !body.get("count").isJsonNull() ? body.get("count").getAsInt() : 1;

                            JsonObject craftRes = com.alcyone.jarvis.crafting.CraftingManager.craftItem(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, item, count);

                            JsonObject res = new JsonObject();
                            boolean success = craftRes.has("success") && craftRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "craft");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", craftRes);

                            return ManagerResult.ok(res);
                        }

                        case "auto_replenish":
                        case "replenish_tools": {
                            JsonObject repRes = com.alcyone.jarvis.crafting.CraftingManager.autoReplenishTools(
                                    (ServerLevel) activeCompanion.level(), activeCompanion);

                            JsonObject res = new JsonObject();
                            boolean success = repRes.has("success") && repRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "auto_replenish");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", repRes);

                            return ManagerResult.ok(res);
                        }

                        case "tech_process_ores":
                        case "process_ores_tech": {
                            String filter = body.has("filter") && !body.get("filter").isJsonNull()
                                    ? body.get("filter").getAsString() : "all";
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 32;

                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject techRes = com.alcyone.jarvis.tech.ModdedTechManager.processOres(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, filter);

                            JsonObject res = new JsonObject();
                            boolean success = techRes.has("success") && techRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "tech_process_ores");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", techRes);

                            return ManagerResult.ok(res);
                        }

                        case "tech_scan_machines":
                        case "scan_machines": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 32;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject scanRes = com.alcyone.jarvis.tech.ModdedTechManager.scanMachines(
                                    (ServerLevel) activeCompanion.level(), searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = scanRes.has("success") && scanRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "tech_scan_machines");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", scanRes);

                            return ManagerResult.ok(res);
                        }

                        case "light_up_area":
                        case "light_up":
                        case "place_torches": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 20;
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject lightRes = com.alcyone.jarvis.exploration.SpelunkingManager.lightUpArea(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius);

                            JsonObject res = new JsonObject();
                            boolean success = lightRes.has("success") && lightRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "light_up_area");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", lightRes);

                            return ManagerResult.ok(res);
                        }

                        case "neutralize_spawners":
                        case "clear_spawners": {
                            int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 24;
                            boolean breakSpawner = body.has("break_spawner") && body.get("break_spawner").getAsBoolean();
                            BlockPos searchCenter = activeCompanion.blockPosition();
                            if (body.has("x") && body.has("y") && body.has("z")) {
                                searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                            }

                            JsonObject spawnerRes = com.alcyone.jarvis.exploration.SpelunkingManager.neutralizeSpawners(
                                    (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, breakSpawner);

                            JsonObject res = new JsonObject();
                            boolean success = spawnerRes.has("success") && spawnerRes.get("success").getAsBoolean();
                            res.addProperty("success", success);
                            res.addProperty("action", "neutralize_spawners");
                            res.addProperty("status", success ? "executed" : "failed");
                            res.add("details", spawnerRes);

                            return ManagerResult.ok(res);
                        }

                        default:
                            return ManagerResult.error(400, "Unknown action: " + action);
                    }
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out executing action: " + action);
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error executing companion action: {}", action, e);
            return ManagerResult.error(500, "Failed to execute action: " + e.getMessage());
        }
    }

    /**
     * Queries companion 27-slot internal inventory and currently equipped armor/weapons/shields.
     */
    public ManagerResult getInventory(MinecraftServer server) {
        if (!this.isSpawned) {
            return ManagerResult.error(400, "Companion is not spawned");
        }

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        reset();
                        return ManagerResult.error(400, "Companion is not spawned");
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("companion_id", this.companionId != null ? this.companionId : "jarvis-1");
                    result.addProperty("size", JarvisCompanionEntity.INVENTORY_SIZE);

                    JsonArray slotsArray = new JsonArray();
                    ItemStackHandler inv = activeCompanion.getInventory();
                    for (int i = 0; i < inv.getSlots(); i++) {
                        ItemStack stack = inv.getStackInSlot(i);
                        if (!stack.isEmpty()) {
                            JsonObject itemObj = new JsonObject();
                            itemObj.addProperty("slot", i);
                            itemObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                            itemObj.addProperty("count", stack.getCount());
                            itemObj.addProperty("name", stack.getHoverName().getString());
                            itemObj.addProperty("damage", stack.getDamageValue());
                            itemObj.addProperty("max_damage", stack.getMaxDamage());
                            slotsArray.add(itemObj);
                        }
                    }
                    result.add("slots", slotsArray);

                    JsonObject equipObj = new JsonObject();
                    equipObj.add("mainhand", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.MAINHAND)));
                    equipObj.add("offhand", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.OFFHAND)));
                    equipObj.add("head", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.HEAD)));
                    equipObj.add("chest", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.CHEST)));
                    equipObj.add("legs", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.LEGS)));
                    equipObj.add("feet", serializeEquipmentStack(activeCompanion.getItemBySlot(EquipmentSlot.FEET)));
                    result.add("equipment", equipObj);

                    return ManagerResult.ok(result);
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out querying inventory");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error querying companion inventory", e);
            return ManagerResult.error(500, "Failed to query inventory: " + e.getMessage());
        }
    }

    private JsonObject serializeEquipmentStack(ItemStack stack) {
        JsonObject obj = new JsonObject();
        if (stack.isEmpty()) {
            obj.addProperty("empty", true);
            return obj;
        }
        obj.addProperty("empty", false);
        obj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        obj.addProperty("count", stack.getCount());
        obj.addProperty("name", stack.getHoverName().getString());
        obj.addProperty("damage", stack.getDamageValue());
        obj.addProperty("max_damage", stack.getMaxDamage());
        return obj;
    }

    public ManagerResult retrieve(MinecraftServer server, JsonObject body) {
        if (!this.isSpawned) {
            return ManagerResult.error(400, "Companion is not spawned");
        }

        String item = body.has("item") ? body.get("item").getAsString()
                : body.has("item_id") ? body.get("item_id").getAsString() : null;
        if (item == null || item.isBlank()) {
            return ManagerResult.error(400, "Missing item identifier for retrieve");
        }
        int count = body.has("count") && !body.get("count").isJsonNull() ? body.get("count").getAsInt() : 1;

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        reset();
                        return ManagerResult.error(400, "Companion is not spawned");
                    }

                    ServerPlayer player = resolvePlayer(server, body);
                    BlockPos searchCenter = activeCompanion.blockPosition();
                    if (body.has("x") && body.has("y") && body.has("z")) {
                        searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                    }

                    JsonObject retRes = com.alcyone.jarvis.storage.StorageManager.retrieveItem(
                            (ServerLevel) activeCompanion.level(), searchCenter, activeCompanion, player, item, count);

                    return ManagerResult.ok(retRes);
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out retrieving items");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error retrieving item", e);
            return ManagerResult.error(500, "Failed to retrieve item: " + e.getMessage());
        }
    }

    public ManagerResult deposit(MinecraftServer server, JsonObject body) {
        if (!this.isSpawned) {
            return ManagerResult.error(400, "Companion is not spawned");
        }

        String category = body.has("category") && !body.get("category").isJsonNull()
                ? body.get("category").getAsString()
                : body.has("filter") && !body.get("filter").isJsonNull()
                ? body.get("filter").getAsString() : "all";
        int radius = body.has("radius") && !body.get("radius").isJsonNull() ? body.get("radius").getAsInt() : 32;

        try {
            return ThreadHelper.supplyOnMain(server, () -> {
                lock.lock();
                try {
                    if (activeCompanion == null || !activeCompanion.isAlive()) {
                        reset();
                        return ManagerResult.error(400, "Companion is not spawned");
                    }

                    BlockPos searchCenter = activeCompanion.blockPosition();
                    if (body.has("x") && body.has("y") && body.has("z")) {
                        searchCenter = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                    }

                    JsonObject depRes = com.alcyone.jarvis.storage.StorageManager.depositLoot(
                            (ServerLevel) activeCompanion.level(), activeCompanion, searchCenter, radius, category);

                    return ManagerResult.ok(depRes);
                } finally {
                    lock.unlock();
                }
            }, 5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ManagerResult.error(504, "Server main thread timed out depositing items");
        } catch (Exception e) {
            LOGGER.error("[Jarvis] Error depositing items", e);
            return ManagerResult.error(500, "Failed to deposit items: " + e.getMessage());
        }
    }

    private BlockPos extractBlockPos(JsonObject body, String key) {
        if (body.has(key) && body.get(key).isJsonObject()) {
            JsonObject obj = body.getAsJsonObject(key);
            if (obj.has("x") && obj.has("y") && obj.has("z")) {
                return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
            }
        }
        String xKey = key + "_x";
        String yKey = key + "_y";
        String zKey = key + "_z";
        if (body.has(xKey) && body.has(yKey) && body.has(zKey)) {
            return new BlockPos(body.get(xKey).getAsInt(), body.get(yKey).getAsInt(), body.get(zKey).getAsInt());
        }
        return null;
    }

    private ServerPlayer resolvePlayer(MinecraftServer server, JsonObject body) {
        if (body.has("player") && !body.get("player").isJsonNull() && !body.get("player").getAsString().isBlank()) {
            String pName = body.get("player").getAsString().trim();
            ServerPlayer p = server.getPlayerList().getPlayerByName(pName);
            if (p != null) return p;
        }
        if (activeCompanion != null && activeCompanion.getFollowTarget() instanceof ServerPlayer sp) {
            return sp;
        }
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            return server.getPlayerList().getPlayers().get(0);
        }
        return null;
    }

    private ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        if (dimension == null || dimension.isBlank()) return server.overworld();
        ResourceLocation loc = ResourceLocation.tryParse(dimension);
        if (loc == null) return server.overworld();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        return level != null ? level : server.overworld();
    }
}
