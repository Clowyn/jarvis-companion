package com.alcyone.jarvis.entity;

import com.alcyone.jarvis.entity.ai.CompanionCombatGoal;
import com.alcyone.jarvis.entity.ai.CompanionFollowPlayerGoal;
import com.alcyone.jarvis.entity.ai.CompanionOwnerHurtByTargetGoal;
import com.alcyone.jarvis.entity.ai.CompanionOwnerHurtTargetGoal;
import com.alcyone.jarvis.entity.ai.CompanionWalkToPosGoal;
import com.alcyone.jarvis.gui.ItemStackHandlerContainer;
import com.alcyone.jarvis.gui.JarvisControlMenu;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Physical body of the Jarvis AI Companion in the Minecraft world.
 * Extends PathfinderMob with 27-slot internal inventory, defensive reflexes (shield block, totem of undying,
 * emergency healing), rebalanced survival attributes, and intelligent target selector goals.
 */
public class JarvisCompanionEntity extends PathfinderMob implements OwnableEntity {

    public static final int INVENTORY_SIZE = 27;

    public enum State {
        UNSPAWNED,
        IDLE,
        NAVIGATING,
        FOLLOWING
    }

    private State companionState = State.IDLE;
    private BlockPos moveTarget = null;
    private double moveSpeed = 1.0D;
    private Player followTarget = null;
    private double followDistance = 3.0D;

    private int stuckTicks = 0;
    private Vec3 lastRecordedPos = Vec3.ZERO;

    private boolean guardMode = true;
    private BlockPos sentryPost = null;

    // Tactical Shield & Combat cadence fields
    private int attackCooldownTicks = 0;
    private int shieldHoldTicks = 0;
    private long lastHurtTimestamp = 0;

    public void notifyAttackCooldown(int ticks) {
        this.attackCooldownTicks = ticks;
    }

    // Internal 27-slot ItemStackHandler inventory capability
    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
        }
    };

    public JarvisCompanionEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
    }

    public static final double BASE_MAX_HEALTH = 100.0D;
    public static final double BASE_ARMOR = 20.0D;
    public static final double BASE_ARMOR_TOUGHNESS = 12.0D;
    public static final double BASE_KNOCKBACK_RESISTANCE = 0.75D;
    public static final double BASE_MOVEMENT_SPEED = 0.32D;
    public static final double BASE_FOLLOW_RANGE = 64.0D;
    public static final double BASE_ATTACK_DAMAGE = 14.0D;
    public static final double BASE_STEP_HEIGHT = 1.25D;

    /**
     * Pillar 1: Rebalanced Attributes for High-Difficulty Modded & Boss Survival.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, BASE_MAX_HEALTH)                      // 50 hearts (100 HP), highly durable against DW20 bosses & champion mobs
            .add(Attributes.ARMOR, BASE_ARMOR)                                // 20.0 base armor (full diamond armor tier)
            .add(Attributes.ARMOR_TOUGHNESS, BASE_ARMOR_TOUGHNESS)            // 12.0 base armor toughness (resists armor-reduction from heavy attacks)
            .add(Attributes.KNOCKBACK_RESISTANCE, BASE_KNOCKBACK_RESISTANCE)  // 75% knockback resistance (anti-juggling)
            .add(Attributes.MOVEMENT_SPEED, BASE_MOVEMENT_SPEED)              // Agile 0.32 speed
            .add(Attributes.FOLLOW_RANGE, BASE_FOLLOW_RANGE)
            .add(Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE)                // 14.0 base strike damage
            .add(Attributes.STEP_HEIGHT, BASE_STEP_HEIGHT);                   // 1.25m smooth step height
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        return nav;
    }

    @Override
    protected void registerGoals() {
        // Core navigation & survival behaviors
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new CompanionCombatGoal(this, 1.30D, true));
        this.goalSelector.addGoal(3, new CompanionWalkToPosGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new CompanionFollowPlayerGoal(this, 1.15D, 3.0F, 6.0F, 32.0F));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        // Intelligent Target Selector AI Goals:
        // Priority 1: Defend player when struck
        this.targetSelector.addGoal(1, new CompanionOwnerHurtByTargetGoal(this));
        // Priority 2: Focus the player's attack target
        this.targetSelector.addGoal(2, new CompanionOwnerHurtTargetGoal(this));
        // Priority 3: Retaliate against any entity that attacked Jarvis
        this.targetSelector.addGoal(3, new HurtByTargetGoal(this).setAlertOthers());
        // Priority 4: Sentry perimeter guard against hostile monsters
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false, this::isHostileTarget));
    }

    /**
     * Determines whether a living entity is a valid hostile target for sentry perimeter defense.
     */
    public boolean isHostileTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target == this) return false;
        if (target instanceof Player) return false;
        if (target instanceof JarvisCompanionEntity) return false;

        // Do not attack allied or player-owned pets
        if (target instanceof OwnableEntity ownable && ownable.getOwner() != null) {
            LivingEntity myOwner = this.getOwner();
            if (myOwner != null && ownable.getOwner() == myOwner) {
                return false;
            }
        }

        // Do not provoke neutral mobs (Enderman, Piglin, Zombified Piglin) unless they are angry
        if (target instanceof net.minecraft.world.entity.NeutralMob neutral && !neutral.isAngry()) {
            return false;
        }

        // Target all hostile monsters including Creepers
        return target instanceof Monster;
    }

    @Override
    public LivingEntity getOwner() {
        if (this.followTarget != null && this.followTarget.isAlive()) {
            return this.followTarget;
        }
        Player nearest = this.level().getNearestPlayer(this, 32.0D);
        if (nearest != null && nearest.isAlive()) {
            return nearest;
        }
        return null;
    }

    @Override
    public UUID getOwnerUUID() {
        LivingEntity owner = getOwner();
        return owner != null ? owner.getUUID() : null;
    }

    public ItemStackHandler getInventory() {
        return this.inventory;
    }

    public boolean isGuardMode() {
        return this.guardMode;
    }

    public void setGuardMode(boolean guardMode) {
        this.guardMode = guardMode;
    }

    public BlockPos getSentryPost() {
        return this.sentryPost;
    }

    public void setSentryPost(BlockPos sentryPost) {
        this.sentryPost = sentryPost;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                // Shift + Right Click: Direct access to 27-slot Internal Inventory
                openInventory(serverPlayer);
            } else {
                // Normal Right Click: Open Interactive Jarvis Control Panel & Command GUI
                openControlMenu(serverPlayer);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    public void openControlMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
            (containerId, playerInventory, p) -> new JarvisControlMenu(containerId, playerInventory, this),
            Component.literal("§6§lJARVIS §8- §eKontrol Paneli")
        ));
    }

    public void openInventory(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
            (containerId, playerInventory, p) -> ChestMenu.threeRows(containerId, playerInventory, new ItemStackHandlerContainer(this.inventory, this)),
            Component.literal("§6§lJARVIS §8- §eDahili Çanta (27 Yuva)")
        ));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.put("Inventory", this.inventory.serializeNBT(this.registryAccess()));
        compound.putBoolean("GuardMode", this.guardMode);
        if (this.sentryPost != null) {
            compound.putInt("SentryX", this.sentryPost.getX());
            compound.putInt("SentryY", this.sentryPost.getY());
            compound.putInt("SentryZ", this.sentryPost.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("Inventory")) {
            this.inventory.deserializeNBT(this.registryAccess(), compound.getCompound("Inventory"));
            autoEquip();
        }
        if (compound.contains("GuardMode")) {
            this.guardMode = compound.getBoolean("GuardMode");
        }
        if (compound.contains("SentryX") && compound.contains("SentryY") && compound.contains("SentryZ")) {
            this.sentryPost = new BlockPos(compound.getInt("SentryX"), compound.getInt("SentryY"), compound.getInt("SentryZ"));
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (!this.level().isClientSide && this.isAlive()) {
            checkStuckRecovery();

            // Passive Auto-Regeneration (Vital Systems Nanotech Repair)
            if (this.tickCount % 20 == 0 && this.getHealth() < this.getMaxHealth()) {
                float regenAmount = (this.tickCount - this.lastHurtTimestamp > 60) ? 3.0F : 1.5F;
                this.heal(regenAmount);
            }

            // Periodic auto-equip check (every second / 20 ticks)
            if (this.tickCount % 20 == 0) {
                autoEquip();
            }

            // Periodic emergency heal check if low on health (< 30%)
            if (this.tickCount % 10 == 0 && this.getHealth() < this.getMaxHealth() * 0.30F) {
                tryEmergencyHeal();
            }

            // Periodic debuff cleanse (every 40 ticks / 2 seconds)
            if (this.tickCount % 40 == 0) {
                purgeHarmfulDebuffs();
            }

            // Tactical Shield Combat update (every tick)
            updateTacticalShieldState();

            // Passive ground item pickup (every 5 ticks / 4 times per second)
            if (this.tickCount % 5 == 0) {
                pickUpNearbyItems();
            }

            // Periodic Overhead Hologram HUD update (every 10 ticks / 2 times per second)
            if (this.tickCount % 10 == 0) {
                updateHologramHud();
            }

            // Pillar 2: Autonomous Building Engine tick
            com.alcyone.jarvis.building.BuildManager.getInstance().tick(this);
        }
    }

    /**
     * Purges debilitating modded and vanilla debuffs (Wither, Poison, Weakness, Slowness).
     */
    public void purgeHarmfulDebuffs() {
        if (this.hasEffect(MobEffects.POISON)) {
            this.removeEffect(MobEffects.POISON);
        }
        if (this.hasEffect(MobEffects.WITHER)) {
            this.removeEffect(MobEffects.WITHER);
        }
        if (this.hasEffect(MobEffects.WEAKNESS)) {
            this.removeEffect(MobEffects.WEAKNESS);
        }
        if (this.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
            this.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    /**
     * Intelligent Tactical Shield Management:
     * Actively raises shield in offhand when:
     * 1. Incoming hostile projectiles are detected within 12 blocks.
     * 2. Creepers are swelling or close nearby.
     * 3. Engaging ranged attackers (skeletons, pillagers).
     * 4. In close melee combat with hostile mobs while waiting on attack cooldown.
     */
    public void updateTacticalShieldState() {
        if (this.level().isClientSide || !this.isAlive()) return;

        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
        }

        ItemStack offhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!isShield(offhand)) {
            ensureShieldEquipped();
            offhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
        }

        boolean hasShield = isShield(offhand);
        if (!hasShield) {
            if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) {
                this.stopUsingItem();
            }
            return;
        }

        boolean shouldBlock = false;
        Entity threatEntity = null;

        // 1. Detect incoming hostile projectiles within 12 blocks
        List<Projectile> nearbyProjectiles = this.level().getEntitiesOfClass(
                Projectile.class,
                this.getBoundingBox().inflate(12.0D),
                p -> p.isAlive() && p.getOwner() != this && p.getOwner() != this.getOwner()
        );

        for (Projectile proj : nearbyProjectiles) {
            Vec3 projVel = proj.getDeltaMovement();
            Vec3 toJarvis = this.position().subtract(proj.position());
            if (projVel.dot(toJarvis) > 0.05) {
                shouldBlock = true;
                threatEntity = proj;
                shieldHoldTicks = 20;
                break;
            }
        }

        // 2. Detect primed/swelling Creepers within 6 blocks
        if (!shouldBlock) {
            List<net.minecraft.world.entity.monster.Creeper> nearbyCreepers = this.level().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Creeper.class,
                    this.getBoundingBox().inflate(6.0D),
                    c -> c.isAlive() && (c.getSwellDir() > 0 || this.distanceToSqr(c) < 3.5 * 3.5)
            );
            if (!nearbyCreepers.isEmpty()) {
                shouldBlock = true;
                threatEntity = nearbyCreepers.get(0);
                shieldHoldTicks = 25;
            }
        }

        // 3. Active Combat Threat Defense
        if (!shouldBlock) {
            LivingEntity target = this.getTarget();
            if (target != null && target.isAlive()) {
                double distSq = this.distanceToSqr(target);

                if (target instanceof net.minecraft.world.entity.monster.RangedAttackMob && distSq > 3.0 * 3.0) {
                    shouldBlock = true;
                    threatEntity = target;
                    shieldHoldTicks = 15;
                } else if (distSq <= 3.5 * 3.5) {
                    // Block while on attack cooldown
                    if (attackCooldownTicks > 2) {
                        shouldBlock = true;
                        threatEntity = target;
                        shieldHoldTicks = 6;
                    }
                }
            }
        }

        if (shouldBlock && threatEntity != null) {
            this.getLookControl().setLookAt(threatEntity, 60.0F, 60.0F);
            if (!this.isUsingItem()) {
                this.startUsingItem(InteractionHand.OFF_HAND);
            }
        } else if (shieldHoldTicks > 0) {
            shieldHoldTicks--;
        } else {
            if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) {
                this.stopUsingItem();
            }
        }
    }

    /**
     * Pillar 1: Self-Defense Overhaul: Shield Blocking, Totem of Undying,
     * Stark Kinetic Barrier & Emergency Healing Reflexes.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source) || this.level().isClientSide || !this.isAlive()) {
            return super.hurt(source, amount);
        }

        this.lastHurtTimestamp = this.tickCount;

        // Environmental Damage Mitigations:
        if (source.is(DamageTypeTags.IS_FALL)) {
            amount *= 0.15F; // 85% fall damage reduction
            if (amount < 1.0F) return false;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            amount *= 0.20F; // 80% fire/lava damage reduction
            this.clearFire();
            if (amount < 1.0F) return false;
        }
        if (source.is(DamageTypeTags.IS_DROWNING) || source.is(DamageTypeTags.IS_FREEZING)) {
            return false;
        }

        boolean blockable = !source.is(DamageTypeTags.BYPASSES_SHIELD) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);

        // 1. Tactical Shield Defense
        if (blockable) {
            ensureShieldEquipped();
            ItemStack offhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
            if (isShield(offhand)) {
                if (!this.isUsingItem()) {
                    this.startUsingItem(InteractionHand.OFF_HAND);
                }

                this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.9F + this.random.nextFloat() * 0.2F);

                // Modded mob damage protection for shield durability:
                // Cap durability loss to maximum 2 points per hit, with 50% nanotech absorption.
                if (this.random.nextBoolean()) {
                    int durabilityLoss = Math.min(2, Math.max(1, (int) (amount * 0.05F)));
                    offhand.hurtAndBreak(durabilityLoss, this, EquipmentSlot.OFFHAND);
                }

                // Nanotech auto-repair: if shield drops below 20% durability, restore 30% durability
                if (offhand.isDamageableItem() && offhand.getDamageValue() > offhand.getMaxDamage() * 0.80) {
                    offhand.setDamageValue((int) (offhand.getMaxDamage() * 0.50));
                }

                // Shield Bash / Tactical Counter-Stun
                Entity direct = source.getDirectEntity();
                Entity attacker = source.getEntity();
                LivingEntity enemy = (direct instanceof LivingEntity l) ? l : ((attacker instanceof LivingEntity l) ? l : null);

                if (enemy != null && enemy != this) {
                    double dx = enemy.getX() - this.getX();
                    double dz = enemy.getZ() - this.getZ();
                    enemy.knockback(0.8D, -dx, -dz);
                    enemy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1)); // Slowness II for 1.5s
                    if (enemy instanceof net.minecraft.world.entity.monster.Creeper creeper) {
                        creeper.knockback(2.0D, -dx, -dz);
                    }
                    this.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 0.8F, 1.2F);
                }

                if (this.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.CRIT,
                            this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.25, 0.25, 0.25, 0.1);
                }

                // 100% damage blocked
                return false;
            } else {
                // Integrated Stark Kinetic Energy Barrier fallback when no physical shield is present!
                if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
                    amount *= 0.30F; // 70% kinetic absorption!
                    this.playSound(SoundEvents.BEACON_DEACTIVATE, 0.7F, 1.8F);
                    this.playSound(SoundEvents.SHIELD_BLOCK, 0.8F, 1.6F);
                    if (this.level() instanceof ServerLevel serverLevel) {
                        serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                                this.getX(), this.getY() + 1.0, this.getZ(), 8, 0.3, 0.4, 0.3, 0.05);
                    }
                }
            }
        }

        // 2. Reflexive Totem of Undying
        if (amount >= this.getHealth() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (tryTriggerTotem()) {
                return false; // Fatal blow prevented!
            }
        }

        // Normal damage pass-through
        boolean hurt = super.hurt(source, amount);

        // 3. Reflexive Emergency Healing (< 30% health)
        if (this.isAlive() && this.getHealth() < this.getMaxHealth() * 0.30F) {
            tryEmergencyHeal();
        }

        return hurt;
    }

    /**
     * Scaled Weapon Damage: Calculates attack damage dynamically based on held weapon,
     * enchantments, critical strikes, and modded boss armor penetration.
     */
    @Override
    public boolean doHurtTarget(Entity target) {
        if (target == null || !target.isAlive()) return false;

        // Temporarily lower shield so the weapon strike connects cleanly
        if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) {
            this.stopUsingItem();
        }

        float baseDamage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty()) {
            double[] bonus = new double[]{0.0};
            mainHand.forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
                if (holder.is(Attributes.ATTACK_DAMAGE)) {
                    bonus[0] += modifier.amount();
                }
            });
            if (bonus[0] > 0.0) {
                baseDamage += (float) bonus[0];
            }
        }

        DamageSource source = this.damageSources().mobAttack(this);

        if (this.level() instanceof ServerLevel serverLevel) {
            baseDamage = EnchantmentHelper.modifyDamage(serverLevel, mainHand, target, source, baseDamage);
        }

        // Stark Tactical Critical Strike (30% chance for 1.5x damage)
        boolean isCrit = this.random.nextFloat() < 0.30F;
        if (isCrit) {
            baseDamage *= 1.5F;
        }

        this.swing(InteractionHand.MAIN_HAND);
        boolean hurt = target.hurt(source, baseDamage);
        if (hurt) {
            if (this.level() instanceof ServerLevel serverLevel) {
                if (isCrit) {
                    serverLevel.sendParticles(ParticleTypes.CRIT,
                            target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                            12, 0.3, 0.3, 0.3, 0.15);
                    this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
                }

                // Modded Boss Armor Penetration: bonus piercing damage if target has high armor (> 10)
                if (target instanceof LivingEntity livingTarget && livingTarget.getArmorValue() > 10) {
                    float piercingDamage = baseDamage * 0.25F;
                    livingTarget.hurt(this.damageSources().magic(), piercingDamage);
                }

                // Sweeping Strike: damage nearby hostile mobs
                List<LivingEntity> nearbyHostiles = serverLevel.getEntitiesOfClass(
                        LivingEntity.class,
                        target.getBoundingBox().inflate(2.5D, 1.0D, 2.5D),
                        e -> e != this && e != target && isHostileTarget(e)
                );
                for (LivingEntity nearby : nearbyHostiles) {
                    nearby.hurt(source, baseDamage * 0.5F);
                    nearby.knockback(0.4D, target.getX() - nearby.getX(), target.getZ() - nearby.getZ());
                }
                if (!nearbyHostiles.isEmpty()) {
                    serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK,
                            target.getX(), target.getY() + 0.5, target.getZ(), 1, 0, 0, 0, 0);
                    this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
                }

                // Anti-Creeper tactical knockback
                if (target instanceof net.minecraft.world.entity.monster.Creeper creeper) {
                    double dx = creeper.getX() - this.getX();
                    double dz = creeper.getZ() - this.getZ();
                    creeper.knockback(1.5D, -dx, -dz);
                }

                EnchantmentHelper.doPostAttackEffects(serverLevel, target, source);
            }
            this.setLastHurtMob(target);
        }
        return hurt;
    }

    /**
     * Scans the internal inventory for higher-tier equipment and auto-equips the best armor, weapon, and shield.
     */
    public void autoEquip() {
        // 1. Armor Auto-Equip (Head, Chest, Legs, Feet)
        autoEquipArmorSlot(EquipmentSlot.HEAD);
        autoEquipArmorSlot(EquipmentSlot.CHEST);
        autoEquipArmorSlot(EquipmentSlot.LEGS);
        autoEquipArmorSlot(EquipmentSlot.FEET);

        // 2. Shield Auto-Equip (Offhand: shield preferred, totem fallback)
        ItemStack currentOffhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!isShield(currentOffhand)) {
            boolean shieldFound = false;
            for (int i = 0; i < this.inventory.getSlots(); i++) {
                ItemStack candidate = this.inventory.getStackInSlot(i);
                if (isShield(candidate)) {
                    ItemStack shield = this.inventory.extractItem(i, 1, false);
                    this.setItemSlot(EquipmentSlot.OFFHAND, shield);
                    if (!currentOffhand.isEmpty()) {
                        insertOrDrop(currentOffhand);
                    }
                    shieldFound = true;
                    break;
                }
            }
            if (!shieldFound && currentOffhand.isEmpty()) {
                for (int i = 0; i < this.inventory.getSlots(); i++) {
                    ItemStack candidate = this.inventory.getStackInSlot(i);
                    if (candidate.is(Items.TOTEM_OF_UNDYING)) {
                        ItemStack totem = this.inventory.extractItem(i, 1, false);
                        this.setItemSlot(EquipmentSlot.OFFHAND, totem);
                        break;
                    }
                }
            }
        }

        // 3. Weapon Auto-Equip (Mainhand)
        ItemStack currentMain = this.getItemBySlot(EquipmentSlot.MAINHAND);
        double currentWeaponScore = getWeaponScore(currentMain);
        int bestWeaponSlot = -1;
        double bestWeaponScore = currentWeaponScore;

        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack candidate = this.inventory.getStackInSlot(i);
            double score = getWeaponScore(candidate);
            if (score > bestWeaponScore) {
                bestWeaponScore = score;
                bestWeaponSlot = i;
            }
        }

        if (bestWeaponSlot != -1) {
            ItemStack newWeapon = this.inventory.extractItem(bestWeaponSlot, 1, false);
            this.setItemSlot(EquipmentSlot.MAINHAND, newWeapon);
            if (!currentMain.isEmpty()) {
                insertOrDrop(currentMain);
            }
        }
    }

    private void autoEquipArmorSlot(EquipmentSlot slot) {
        ItemStack current = this.getItemBySlot(slot);
        double currentScore = getArmorScore(current, slot);
        int bestSlot = -1;
        double bestScore = currentScore;

        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack candidate = this.inventory.getStackInSlot(i);
            if (matchesArmorSlot(candidate, slot)) {
                double score = getArmorScore(candidate, slot);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot != -1) {
            ItemStack newArmor = this.inventory.extractItem(bestSlot, 1, false);
            this.setItemSlot(slot, newArmor);
            if (!current.isEmpty()) {
                insertOrDrop(current);
            }
        }
    }

    private boolean matchesArmorSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return false;
        if (this.getEquipmentSlotForItem(stack) == slot) return true;
        if (stack.getItem() instanceof ArmorItem armorItem) {
            return armorItem.getEquipmentSlot() == slot;
        }
        return false;
    }

    private double getArmorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0.0;
        double defense = 0.0;
        double toughness = 0.0;

        if (stack.getItem() instanceof ArmorItem armorItem) {
            defense = armorItem.getDefense();
            toughness = armorItem.getToughness();
        }

        double[] attrVals = new double[]{0.0, 0.0};
        EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
        stack.forEachModifier(group, (holder, modifier) -> {
            if (holder.is(Attributes.ARMOR)) {
                attrVals[0] += modifier.amount();
            } else if (holder.is(Attributes.ARMOR_TOUGHNESS)) {
                attrVals[1] += modifier.amount();
            }
        });
        if (attrVals[0] > 0) defense = Math.max(defense, attrVals[0]);
        if (attrVals[1] > 0) toughness = Math.max(toughness, attrVals[1]);

        double score = (defense * 10.0) + (toughness * 5.0);

        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            double enchBonus = 0.0;
            for (var entry : enchantments.entrySet()) {
                enchBonus += entry.getIntValue() * 2.0;
            }
            score += enchBonus;
        }

        return score;
    }

    private double getWeaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        double damage = 0.0;

        if (stack.getItem() instanceof SwordItem sword) {
            damage = 10.0 + sword.getTier().getAttackDamageBonus();
        } else if (stack.getItem() instanceof AxeItem axe) {
            damage = 8.0 + axe.getTier().getAttackDamageBonus();
        } else if (stack.getItem() instanceof TieredItem tiered) {
            damage = 4.0 + tiered.getTier().getAttackDamageBonus();
        }

        double[] total = new double[]{0.0};
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (holder, modifier) -> {
            if (holder.is(Attributes.ATTACK_DAMAGE)) {
                total[0] += modifier.amount();
            }
        });
        if (total[0] > 10.0) {
            damage = Math.max(damage, total[0] + 5.0);
        } else if (total[0] > 0.0) {
            damage = Math.max(damage, total[0]);
        }

        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            double enchBonus = 0.0;
            for (var entry : enchantments.entrySet()) {
                enchBonus += entry.getIntValue() * 1.5;
            }
            damage += enchBonus;
        }

        return damage;
    }

    public static boolean isShield(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof ShieldItem || stack.canPerformAction(ItemAbilities.SHIELD_BLOCK);
    }

    private void ensureShieldEquipped() {
        ItemStack offhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
        if (isShield(offhand)) return;

        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (isShield(stack)) {
                ItemStack shield = this.inventory.extractItem(i, 1, false);
                this.setItemSlot(EquipmentSlot.OFFHAND, shield);
                if (!offhand.isEmpty()) {
                    insertOrDrop(offhand);
                }
                return;
            }
        }

        // Stark Nanotech Synthesis: if completely shieldless, synthesize an emergency tactical shield
        if (offhand.isEmpty()) {
            this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            this.playSound(SoundEvents.IRON_GOLEM_REPAIR, 0.8F, 1.2F);
        }
    }

    private boolean tryTriggerTotem() {
        // Check offhand first
        ItemStack offhand = this.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            offhand.shrink(1);
            applyTotemResurrection();
            return true;
        }

        // Check internal inventory: auto-equip to offhand and trigger native totem resurrection
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                ItemStack totem = this.inventory.extractItem(i, 1, false);
                if (!totem.isEmpty()) {
                    if (!offhand.isEmpty()) {
                        insertOrDrop(offhand);
                    }
                    this.setItemSlot(EquipmentSlot.OFFHAND, totem);
                    totem.shrink(1);
                    applyTotemResurrection();
                    return true;
                }
            }
        }

        return false;
    }

    private void applyTotemResurrection() {
        this.setHealth(1.0F);
        this.removeAllEffects();
        this.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        this.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        this.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
        this.level().broadcastEntityEvent(this, (byte) 35);
        this.playSound(SoundEvents.TOTEM_USE, 1.0F, 1.0F);
    }

    /**
     * Emergency Heal Reflex: Consumes golden apples or healing potions when health drops below 30%.
     */
    public boolean tryEmergencyHeal() {
        // Priority 1: Enchanted Golden Apple
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                this.inventory.extractItem(i, 1, false);
                this.heal(8.0F);
                this.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
                this.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 3));
                this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 0));
                this.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
                this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                return true;
            }
        }

        // Priority 2: Golden Apple
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (stack.is(Items.GOLDEN_APPLE)) {
                this.inventory.extractItem(i, 1, false);
                this.heal(4.0F);
                this.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                this.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0));
                this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                return true;
            }
        }

        // Priority 3: Healing / Regeneration Potions
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null) {
                boolean isHealing = contents.is(Potions.HEALING) || contents.is(Potions.STRONG_HEALING);
                boolean isRegen = contents.is(Potions.REGENERATION) || contents.is(Potions.STRONG_REGENERATION);
                if (isHealing || isRegen) {
                    this.inventory.extractItem(i, 1, false);
                    if (contents.is(Potions.STRONG_HEALING)) {
                        this.heal(8.0F);
                    } else if (contents.is(Potions.HEALING)) {
                        this.heal(4.0F);
                    }
                    contents.forEachEffect(effect -> this.addEffect(new MobEffectInstance(effect)));
                    this.playSound(SoundEvents.GENERIC_DRINK, 1.0F, 1.0F);
                    return true;
                }
            }
        }

        // Priority 4: Nutritious Food Items
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            var food = stack.get(DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                this.inventory.extractItem(i, 1, false);
                this.heal((float) (food.nutrition() * 1.5F));
                this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                return true;
            }
        }

        return false;
    }

    public ItemStack insertIntoInventory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            remaining = this.inventory.insertItem(i, remaining, false);
            if (remaining.isEmpty()) break;
        }
        return remaining;
    }

    private void insertOrDrop(ItemStack stack) {
        ItemStack remaining = insertIntoInventory(stack);
        if (!remaining.isEmpty()) {
            this.spawnAtLocation(remaining);
        }
    }

    /**
     * Passively picks up nearby dropped items from the ground into Jarvis's 27-slot internal inventory,
     * behaving like a true living mob companion.
     */
    public void pickUpNearbyItems() {
        if (!this.isAlive() || this.level().isClientSide) return;

        List<ItemEntity> nearbyItems = this.level().getEntitiesOfClass(
                ItemEntity.class,
                this.getBoundingBox().inflate(2.0D, 1.0D, 2.0D),
                item -> item.isAlive() && !item.hasPickUpDelay()
        );

        for (ItemEntity item : nearbyItems) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) continue;

            int originalCount = stack.getCount();
            ItemStack remaining = insertIntoInventory(stack);
            int collected = originalCount - remaining.getCount();

            if (collected > 0) {
                this.take(item, collected);
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                        (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
            }

            if (remaining.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remaining);
            }
        }
    }

    /**
     * Empties the companion's internal inventory and delivers all items to the player.
     * Any items that do not fit into the player's inventory are dropped at the player's feet.
     */
    public List<ItemStack> deliverInventoryToPlayer(ServerPlayer player) {
        List<ItemStack> delivered = new ArrayList<>();
        if (player == null) return delivered;

        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                ItemStack extracted = this.inventory.extractItem(i, stack.getCount(), false);
                if (!extracted.isEmpty()) {
                    delivered.add(extracted.copy());
                    boolean added = player.getInventory().add(extracted);
                    if (!added && !extracted.isEmpty()) {
                        player.drop(extracted, false);
                    }
                }
            }
        }

        if (!delivered.isEmpty()) {
            this.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.0F);
        }

        return delivered;
    }

    /**
     * 3-Tier Stuck Recovery Engine
     */
    private void checkStuckRecovery() {
        boolean isNavigating = !this.getNavigation().isDone();
        if (!isNavigating) {
            stuckTicks = 0;
            return;
        }

        if (this.tickCount % 20 == 0) {
            Vec3 currentPos = this.position();
            double distSq = currentPos.distanceToSqr(this.lastRecordedPos);
            this.lastRecordedPos = currentPos;

            if (distSq < 0.15 * 0.15) {
                stuckTicks += 20;

                if (stuckTicks == 40) {
                    this.getNavigation().recomputePath();
                } else if (stuckTicks == 80) {
                    this.getJumpControl().jump();
                } else if (stuckTicks >= 120) {
                    recoverFromStuck();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
        }
    }

    private void recoverFromStuck() {
        if (this.hasMoveTarget()) {
            BlockPos target = this.getMoveTarget();
            if (target != null) {
                if (this.distanceToSqr(Vec3.atCenterOf(target)) < 16.0 * 16.0) {
                    this.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                    this.clearMoveTarget();
                    this.getNavigation().stop();
                    return;
                }
            }
        }
        this.getNavigation().recomputePath();
    }

    public void teleportNearPlayer(Player player) {
        if (player == null || !player.isAlive()) return;

        double angle = this.random.nextDouble() * Math.PI * 2;
        double radius = 2.0D;
        double targetX = player.getX() + Math.cos(angle) * radius;
        double targetZ = player.getZ() + Math.sin(angle) * radius;
        double targetY = player.getY();

        this.teleportTo(targetX, targetY, targetZ);
        this.getNavigation().stop();
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        if (!this.level().isClientSide()) {
            CompanionManager.getInstance().onEntityRemoved(this);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (!this.level().isClientSide()) {
            CompanionManager.getInstance().onEntityRemoved(this);
        }
    }

    // State and navigation accessors
    public State getCompanionState() {
        return companionState;
    }

    public void setCompanionState(State companionState) {
        this.companionState = companionState;
    }

    public boolean hasMoveTarget() {
        return this.moveTarget != null;
    }

    public BlockPos getMoveTarget() {
        return this.moveTarget;
    }

    public double getMoveSpeed() {
        return this.moveSpeed;
    }

    public void setMoveTarget(BlockPos target, double speed) {
        this.moveTarget = target;
        this.moveSpeed = speed;
        this.followTarget = null;
        this.setCompanionState(State.NAVIGATING);
    }

    public void clearMoveTarget() {
        this.moveTarget = null;
        if (this.companionState == State.NAVIGATING) {
            this.setCompanionState(State.IDLE);
        }
    }

    public boolean isNavigationFinished() {
        return this.getNavigation().isDone();
    }

    public Player getFollowTarget() {
        return this.followTarget;
    }

    public void setFollowTarget(Player player, double distance) {
        this.followTarget = player;
        this.followDistance = distance;
        this.moveTarget = null;
        this.setCompanionState(State.FOLLOWING);
    }

    public void clearFollowTarget() {
        this.followTarget = null;
        if (this.companionState == State.FOLLOWING) {
            this.setCompanionState(State.IDLE);
        }
    }

    public void stopAllMovement() {
        this.moveTarget = null;
        this.followTarget = null;
        this.setTarget(null);
        this.getNavigation().stop();
        this.setCompanionState(State.IDLE);
    }

    public JsonObject getTargetJson() {
        if (this.companionState == State.NAVIGATING && this.moveTarget != null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", (double) this.moveTarget.getX());
            obj.addProperty("y", (double) this.moveTarget.getY());
            obj.addProperty("z", (double) this.moveTarget.getZ());
            return obj;
        } else if (this.companionState == State.FOLLOWING && this.followTarget != null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("player", this.followTarget.getScoreboardName());
            obj.addProperty("distance", this.followDistance);
            return obj;
        }
        return null;
    }

    /**
     * Phase 6: Dynamic Overhead Hologram HUD.
     * Updates Jarvis's floating nameplate with live state, health, and backpack status.
     */
    public void updateHologramHud() {
        String stateStr;
        switch (this.companionState) {
            case NAVIGATING -> stateStr = "§bYürüyor";
            case FOLLOWING -> stateStr = "§aTakip Ediyor";
            case IDLE -> stateStr = "§eHazır";
            default -> stateStr = "§7Bekliyor";
        }

        int filledSlots = 0;
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            if (!this.inventory.getStackInSlot(i).isEmpty()) {
                filledSlots++;
            }
        }

        int hp = (int) Math.ceil(this.getHealth());
        int maxHp = (int) this.getMaxHealth();

        Component hud = Component.literal("§6§lJARVIS §8| " + stateStr + " §8| §c❤ " + hp + "/" + maxHp + " §8| §e🎒 " + filledSlots + "/27");
        this.setCustomName(hud);
        this.setCustomNameVisible(true);
    }
}

