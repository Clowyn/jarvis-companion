package com.alcyone.jarvis.entity.ai;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

import java.util.EnumSet;

/**
 * AI target goal that focuses the companion's owner's target.
 * When the owner attacks an entity, Jarvis joins the assault and attacks that same entity.
 */
public class CompanionOwnerHurtTargetGoal extends TargetGoal {
    private final JarvisCompanionEntity companion;
    private LivingEntity ownerLastHurt;
    private int timestamp;

    public CompanionOwnerHurtTargetGoal(JarvisCompanionEntity companion) {
        super(companion, false);
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        LivingEntity owner = this.companion.getOwner();
        if (owner == null) {
            return false;
        }
        this.ownerLastHurt = owner.getLastHurtMob();
        int lastHurtTimestamp = owner.getLastHurtMobTimestamp();
        if (lastHurtTimestamp == this.timestamp || this.ownerLastHurt == null) {
            return false;
        }
        // Do not attack the companion itself or the owner
        if (this.ownerLastHurt == this.companion || this.ownerLastHurt == owner) {
            return false;
        }
        return this.canAttack(this.ownerLastHurt, TargetingConditions.DEFAULT);
    }

    @Override
    public void start() {
        this.mob.setTarget(this.ownerLastHurt);
        LivingEntity owner = this.companion.getOwner();
        if (owner != null) {
            this.timestamp = owner.getLastHurtMobTimestamp();
        }
        super.start();
    }
}
