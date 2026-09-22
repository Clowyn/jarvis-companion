package com.alcyone.jarvis.entity.ai;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * AI goal for navigating to a specific target BlockPos.
 */
public class CompanionWalkToPosGoal extends Goal {
    private final JarvisCompanionEntity companion;
    private final double defaultSpeedModifier;
    private int timeToRecalcPath;

    public CompanionWalkToPosGoal(JarvisCompanionEntity companion, double defaultSpeedModifier) {
        this.companion = companion;
        this.defaultSpeedModifier = defaultSpeedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return companion.hasMoveTarget() && !companion.isNavigationFinished();
    }

    @Override
    public boolean canContinueToUse() {
        if (!companion.hasMoveTarget()) {
            return false;
        }
        BlockPos target = companion.getMoveTarget();
        if (target == null) {
            return false;
        }
        // Check arrival: within 1.5 blocks horizontal and vertical
        double distSq = companion.distanceToSqr(Vec3.atCenterOf(target));
        if (distSq <= 1.5 * 1.5) {
            return false;
        }
        return !companion.getNavigation().isDone();
    }

    @Override
    public void start() {
        this.timeToRecalcPath = 0;
        companion.setCompanionState(JarvisCompanionEntity.State.NAVIGATING);
    }

    @Override
    public void stop() {
        BlockPos target = companion.getMoveTarget();
        if (target != null && companion.distanceToSqr(Vec3.atCenterOf(target)) <= 2.0 * 2.0) {
            companion.clearMoveTarget();
        }
        companion.getNavigation().stop();
        if (companion.getCompanionState() == JarvisCompanionEntity.State.NAVIGATING) {
            companion.setCompanionState(JarvisCompanionEntity.State.IDLE);
        }
    }

    @Override
    public void tick() {
        BlockPos target = companion.getMoveTarget();
        if (target == null) return;

        companion.getLookControl().setLookAt(Vec3.atCenterOf(target));

        if (--this.timeToRecalcPath <= 0) {
            this.timeToRecalcPath = 15; // Recalculate every 15 ticks (0.75s)
            double speed = companion.getMoveSpeed() > 0 ? companion.getMoveSpeed() : this.defaultSpeedModifier;
            companion.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
    }
}
