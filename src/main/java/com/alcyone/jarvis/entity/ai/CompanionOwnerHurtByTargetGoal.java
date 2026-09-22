package com.alcyone.jarvis.entity.ai;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

import java.util.EnumSet;

/**
 * AI target goal that defends the companion's owner.
 * If the owner is struck by a hostile entity, Jarvis targets and attacks the aggressor.
 */
public class CompanionOwnerHurtByTargetGoal extends TargetGoal {
    private final JarvisCompanionEntity companion;
    private LivingEntity ownerLastHurtBy;
    private int timestamp;

    public CompanionOwnerHurtByTargetGoal(JarvisCompanionEntity companion) {
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
        this.ownerLastHurtBy = owner.getLastHurtByMob();
        int lastHurtTimestamp = owner.getLastHurtByMobTimestamp();
        if (lastHurtTimestamp == this.timestamp || this.ownerLastHurtBy == null) {
            return false;
        }
        // Do not attack the companion itself or the owner
        if (this.ownerLastHurtBy == this.companion || this.ownerLastHurtBy == owner) {
            return false;
        }
        return this.canAttack(this.ownerLastHurtBy, TargetingConditions.DEFAULT);
    }

    @Override
    public void start() {
        this.mob.setTarget(this.ownerLastHurtBy);
        LivingEntity owner = this.companion.getOwner();
        if (owner != null) {
            this.timestamp = owner.getLastHurtByMobTimestamp();
        }
        super.start();
    }
}
