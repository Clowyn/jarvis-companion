package com.alcyone.jarvis.entity.ai;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Intelligent Combat AI Goal for Jarvis Companion.
 * Features a faster 10-tick (0.5s) attack cadence, tactical shield coordination
 * (lowering shield to swing weapon with maximum force, raising it on cooldown),
 * and anti-boss aggression.
 */
public class CompanionCombatGoal extends MeleeAttackGoal {
    private final JarvisCompanionEntity companion;

    public CompanionCombatGoal(JarvisCompanionEntity companion, double speedModifier, boolean followingTargetEvenIfNotSeen) {
        super(companion, speedModifier, followingTargetEvenIfNotSeen);
        this.companion = companion;
    }

    @Override
    protected int getAttackInterval() {
        // Highly responsive 10 ticks (0.5s) attack cooldown (2 attacks per second)
        return this.adjustedTickDelay(10);
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity enemy) {
        if (this.canPerformAttack(enemy)) {
            this.resetAttackCooldown();
            this.companion.notifyAttackCooldown(10);

            // Momentarily lower shield so the weapon strike connects cleanly
            if (this.companion.isUsingItem() && this.companion.getUsedItemHand() == InteractionHand.OFF_HAND) {
                this.companion.stopUsingItem();
            }

            this.mob.swing(InteractionHand.MAIN_HAND);
            this.mob.doHurtTarget(enemy);
        }
    }
}
