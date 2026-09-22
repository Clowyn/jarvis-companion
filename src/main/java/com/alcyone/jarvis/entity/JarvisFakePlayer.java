package com.alcyone.jarvis.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandler;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.UUID;

/**
 * Player action execution delegate for Jarvis Companion.
 * Uses FakePlayerFactory to execute player-level mechanics (claim events, Fortune/Silk Touch, placement).
 */
public class JarvisFakePlayer {

    public static final UUID JARVIS_FAKE_PLAYER_UUID = UUID.fromString("6a727669-7300-4000-8000-000000000001");
    public static final GameProfile JARVIS_PROFILE = new GameProfile(JARVIS_FAKE_PLAYER_UUID, "[Jarvis]");

    public record ExecutionResult(boolean success, int statusCode, String action, String errorMessage, JsonObject details) {
        public static ExecutionResult success(String action, JsonObject details) {
            return new ExecutionResult(true, 200, action, null, details);
        }

        public static ExecutionResult failure(int statusCode, String errorMessage) {
            return new ExecutionResult(false, statusCode, null, errorMessage, null);
        }
    }

    public static FakePlayer prepareFakePlayer(JarvisCompanionEntity companion) {
        ServerLevel level = (ServerLevel) companion.level();
        FakePlayer fakePlayer = FakePlayerFactory.get(level, JARVIS_PROFILE);

        fakePlayer.setPos(companion.getX(), companion.getY(), companion.getZ());
        fakePlayer.setXRot(companion.getXRot());
        fakePlayer.setYRot(companion.getYRot());
        fakePlayer.yHeadRot = companion.yHeadRot;
        fakePlayer.yBodyRot = companion.yBodyRot;

        ItemStack mainHand = companion.getMainHandItem().copy();
        ItemStack offHand = companion.getOffhandItem().copy();
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, mainHand);
        fakePlayer.setItemInHand(InteractionHand.OFF_HAND, offHand);

        // Synchronize weapon attribute modifiers onto fakePlayer
        AttributeInstance attackAttr = fakePlayer.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) {
            attackAttr.removeModifiers();
            mainHand.forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
                if (holder.is(Attributes.ATTACK_DAMAGE)) {
                    attackAttr.addTransientModifier(modifier);
                }
            });
        }

        fakePlayer.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        return fakePlayer;
    }

    public static void syncBackToCompanion(FakePlayer fakePlayer, JarvisCompanionEntity companion) {
        companion.setItemSlot(EquipmentSlot.MAINHAND, fakePlayer.getMainHandItem());
        companion.setItemSlot(EquipmentSlot.OFFHAND, fakePlayer.getOffhandItem());
    }

    /**
     * Executes block breaking using fakePlayer.gameMode.destroyBlock.
     */
    public static ExecutionResult executeBreakBlock(JarvisCompanionEntity companion, BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();

        if (!level.hasChunkAt(pos)) {
            return ExecutionResult.failure(400, "Chunk at position " + pos.toShortString() + " is not loaded");
        }

        // Reach limit enforcement: companion must be physically within reach (max 5.5 blocks)
        double distSq = companion.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (distSq > 5.5D * 5.5D) {
            return ExecutionResult.failure(400, "Block at " + pos.toShortString() + " is out of reach (" + String.format("%.1f", Math.sqrt(distSq)) + " blocks away, max 5.5 blocks)");
        }

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return ExecutionResult.failure(400, "Block at " + pos.toShortString() + " is air");
        }
        if (state.getDestroySpeed(level, pos) < 0) {
            return ExecutionResult.failure(400, "Block at " + pos.toShortString() + " is unbreakable (e.g. bedrock)");
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        FakePlayer fakePlayer = prepareFakePlayer(companion);
        if (fakePlayer.getMainHandItem().isEmpty() || !fakePlayer.getMainHandItem().isCorrectToolForDrops(state)) {
            // Provide a diamond pickaxe so valuable ores and stone drop proper items instead of disappearing
            fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        }
        companion.swing(InteractionHand.MAIN_HAND);

        boolean destroyed = fakePlayer.gameMode.destroyBlock(pos);
        syncBackToCompanion(fakePlayer, companion);

        if (!destroyed) {
            // If destroyBlock failed (e.g. claim plugin), fallback to level.destroyBlock
            boolean fallbackDestroyed = level.destroyBlock(pos, true, fakePlayer);
            if (!fallbackDestroyed) {
                return ExecutionResult.failure(400, "Block breaking was cancelled by protection or permissions");
            }
        }

        // Collect dropped ore/block item entities directly into companion's 27-slot inventory!
        try {
            List<ItemEntity> droppedItems = level.getEntitiesOfClass(
                    ItemEntity.class,
                    new AABB(pos).inflate(2.5D)
            );
            for (ItemEntity item : droppedItems) {
                if (!item.isAlive()) continue;
                ItemStack stack = item.getItem();
                int originalCount = stack.getCount();
                ItemStack remaining = companion.insertIntoInventory(stack);
                int collected = originalCount - remaining.getCount();
                if (collected > 0) {
                    companion.take(item, collected);
                    level.playSound(null, companion.getX(), companion.getY(), companion.getZ(),
                            net.minecraft.sounds.SoundEvents.ITEM_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.2F,
                            (companion.getRandom().nextFloat() - companion.getRandom().nextFloat()) * 0.2F + 1.0F);
                }
                if (remaining.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(remaining);
                }
            }
        } catch (Exception ignored) {
        }

        JsonObject details = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        details.add("pos", posObj);
        details.addProperty("dropped", blockId);

        return ExecutionResult.success("break_block", details);
    }

    /**
     * Executes block placement using neighbor support detection and BlockItem.useOn.
     */
    public static ExecutionResult executePlaceBlock(JarvisCompanionEntity companion, BlockPos pos, String itemId) {
        ServerLevel level = (ServerLevel) companion.level();

        if (!level.hasChunkAt(pos)) {
            return ExecutionResult.failure(400, "Chunk at position " + pos.toShortString() + " is not loaded");
        }

        // Reach limit enforcement: companion must be physically within reach (max 5.5 blocks)
        double distSq = companion.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (distSq > 5.5D * 5.5D) {
            return ExecutionResult.failure(400, "Block at " + pos.toShortString() + " is out of reach (" + String.format("%.1f", Math.sqrt(distSq)) + " blocks away, max 5.5 blocks)");
        }

        BlockState currentTargetState = level.getBlockState(pos);
        if (!currentTargetState.canBeReplaced()) {
            return ExecutionResult.failure(400, "Target position " + pos.toShortString() + " is already occupied by " +
                    BuiltInRegistries.BLOCK.getKey(currentTargetState.getBlock()));
        }

        String targetItem = (itemId != null && !itemId.isBlank()) ? itemId : "minecraft:cobblestone";
        ResourceLocation itemLoc = ResourceLocation.tryParse(targetItem);
        if (itemLoc == null || !BuiltInRegistries.ITEM.containsKey(itemLoc)) {
            return ExecutionResult.failure(400, "Unknown item: " + targetItem);
        }

        Item item = BuiltInRegistries.ITEM.get(itemLoc);
        if (!(item instanceof BlockItem blockItem)) {
            return ExecutionResult.failure(400, "Item " + targetItem + " is not a placeable block");
        }

        // Support neighbor detection
        Direction clickedFace = Direction.UP;
        BlockPos neighbor = pos.below();

        Direction[] searchDirs = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        for (Direction dir : searchDirs) {
            BlockPos checkPos = pos.relative(dir);
            BlockState checkState = level.getBlockState(checkPos);
            Direction oppositeFace = dir.getOpposite();
            if (checkState.isFaceSturdy(level, checkPos, oppositeFace)) {
                neighbor = checkPos;
                clickedFace = oppositeFace;
                break;
            }
        }

        FakePlayer fakePlayer = prepareFakePlayer(companion);
        ItemStack placeStack = new ItemStack(blockItem, 1);
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, placeStack);

        Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, clickedFace, neighbor, false);
        UseOnContext context = new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, hitResult);

        InteractionResult result = blockItem.useOn(context);
        if (!result.consumesAction()) {
            // Fallback: direct setBlock
            boolean placed = level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3);
            if (!placed) {
                return ExecutionResult.failure(400, "Failed to place block " + targetItem + " at " + pos.toShortString());
            }
        }

        companion.swing(InteractionHand.MAIN_HAND);
        syncBackToCompanion(fakePlayer, companion);

        JsonObject details = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        details.add("pos", posObj);
        details.addProperty("block", targetItem);

        return ExecutionResult.success("place_block", details);
    }

    /**
     * Executes block interaction.
     */
    public static ExecutionResult executeInteractBlock(JarvisCompanionEntity companion, BlockPos pos) {
        ServerLevel level = (ServerLevel) companion.level();

        if (!level.hasChunkAt(pos)) {
            return ExecutionResult.failure(400, "Chunk at position " + pos.toShortString() + " is not loaded");
        }

        BlockState state = level.getBlockState(pos);
        FakePlayer fakePlayer = prepareFakePlayer(companion);
        companion.swing(InteractionHand.MAIN_HAND);

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        net.minecraft.world.ItemInteractionResult result = state.useItemOn(fakePlayer.getMainHandItem(), level, fakePlayer, InteractionHand.MAIN_HAND, hitResult);
        if (!result.consumesAction()) {
            state.useWithoutItem(level, fakePlayer, hitResult);
        }

        syncBackToCompanion(fakePlayer, companion);

        JsonObject details = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        details.add("pos", posObj);
        details.addProperty("result", "interacted");

        return ExecutionResult.success("interact_block", details);
    }

    /**
     * Inspects container inventories using NeoForge Capabilities.ItemHandler.BLOCK with fallback to Container.
     */
    public static ExecutionResult executeInspectContainer(ServerLevel level, BlockPos pos) {
        JsonObject details = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        details.add("pos", posObj);

        if (!level.hasChunkAt(pos)) {
            details.add("items", new JsonArray());
            return ExecutionResult.success("inspect_container", details);
        }

        JsonArray itemsArray = new JsonArray();

        // Query NeoForge ItemHandler capability (side == null unifies double chests)
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            for (Direction side : Direction.values()) {
                handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
                if (handler != null) break;
            }
        }

        if (handler != null) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    JsonObject itemObj = new JsonObject();
                    itemObj.addProperty("slot", slot);
                    itemObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    itemObj.addProperty("count", stack.getCount());
                    itemObj.addProperty("name", stack.getHoverName().getString());
                    itemsArray.add(itemObj);
                }
            }
        } else {
            // Vanilla Container fallback
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("slot", slot);
                        itemObj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                        itemObj.addProperty("count", stack.getCount());
                        itemObj.addProperty("name", stack.getHoverName().getString());
                        itemsArray.add(itemObj);
                    }
                }
            }
        }

        details.add("items", itemsArray);
        return ExecutionResult.success("inspect_container", details);
    }

    /**
     * Executes attack on living entity by entity ID using actual scaled weapon damage and enchantments.
     */
    public static ExecutionResult executeAttack(JarvisCompanionEntity companion, int entityId) {
        ServerLevel level = (ServerLevel) companion.level();
        Entity target = level.getEntity(entityId);

        if (target == null || !target.isAlive() || !(target instanceof LivingEntity livingTarget)) {
            return ExecutionResult.failure(404, "Entity " + entityId + " not found");
        }

        // Auto-equip highest tier weapon before striking
        companion.autoEquip();

        FakePlayer fakePlayer = prepareFakePlayer(companion);
        companion.swing(InteractionHand.MAIN_HAND);

        // Scaled weapon damage calculation using FakePlayer attributes & enchantment bonuses
        double attackDamage = fakePlayer.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double companionBase = companion.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float damage = (float) Math.max(attackDamage, companionBase);

        DamageSource source = fakePlayer.damageSources().playerAttack(fakePlayer);
        damage = EnchantmentHelper.modifyDamage(level, fakePlayer.getMainHandItem(), livingTarget, source, damage);

        livingTarget.hurt(source, damage);
        EnchantmentHelper.doPostAttackEffects(level, livingTarget, source);
        syncBackToCompanion(fakePlayer, companion);

        float remainingHealth = Math.max(0.0F, livingTarget.getHealth());
        boolean defeated = livingTarget.isDeadOrDying() || remainingHealth <= 0.0F;

        JsonObject details = new JsonObject();
        details.addProperty("target_id", entityId);
        details.addProperty("damage", (double) damage);
        details.addProperty("weapon", fakePlayer.getMainHandItem().getHoverName().getString());
        details.addProperty("remaining_health", (double) remainingHealth);
        details.addProperty("defeated", defeated);

        return ExecutionResult.success("attack", details);
    }
}
