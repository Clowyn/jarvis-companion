package com.alcyone.jarvis.entity.ai;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * AI goal for following a designated player.
 */
public class CompanionFollowPlayerGoal extends Goal {
    private final JarvisCompanionEntity companion;
    private final double speedModifier;
    private final float minDistance;      // Distance to stop moving (e.g. 3.0 blocks)
    private final float maxDistance;      // Distance to start moving (e.g. 6.0 blocks)
    private final float teleportDistance; // Distance to trigger instant catch-up (e.g. 32.0 blocks)
    private int timeToRecalcPath;

    public CompanionFollowPlayerGoal(JarvisCompanionEntity companion, double speedModifier,
                                     float minDistance, float maxDistance, float teleportDistance) {
        this.companion = companion;
        this.speedModifier = speedModifier;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.teleportDistance = teleportDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Player followTarget = companion.getFollowTarget();
        if (followTarget == null || !followTarget.isAlive() || followTarget.isSpectator()) {
            return false;
        }
        return companion.distanceToSqr(followTarget) > (double)(this.maxDistance * this.maxDistance);
    }

    @Override
    public boolean canContinueToUse() {
        Player followTarget = companion.getFollowTarget();
        if (followTarget == null || !followTarget.isAlive() || followTarget.isSpectator()) {
            return false;
        }
        return companion.distanceToSqr(followTarget) > (double)(this.minDistance * this.minDistance);
    }

    @Override
    public void start() {
        this.timeToRecalcPath = 0;
        companion.setCompanionState(JarvisCompanionEntity.State.FOLLOWING);
    }

    @Override
    public void stop() {
        companion.getNavigation().stop();
        if (companion.getCompanionState() == JarvisCompanionEntity.State.FOLLOWING) {
            companion.setCompanionState(JarvisCompanionEntity.State.IDLE);
        }
    }

    @Override
    public void tick() {
        Player followTarget = companion.getFollowTarget();
        if (followTarget == null) return;

        companion.getLookControl().setLookAt(followTarget, 10.0F, (float)companion.getMaxHeadXRot());

        double distSq = companion.distanceToSqr(followTarget);
        if (distSq > (double)(this.teleportDistance * this.teleportDistance)) {
            companion.teleportNearPlayer(followTarget);
            return;
        }

        if (--this.timeToRecalcPath <= 0) {
            this.timeToRecalcPath = 10; // Recalculate every 10 ticks (0.5s)
            companion.getNavigation().moveTo(followTarget, this.speedModifier);
        }
    }
}
