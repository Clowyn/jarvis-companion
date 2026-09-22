package com.alcyone.jarvis.ultron;

import com.alcyone.jarvis.entity.CompanionManager;
import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

/**
 * MCU Ultron Event Protocol.
 * Spooks players by transforming Jarvis into Ultron with glitching text,
 * iconic MCU Ultron quotes in red bold text, eerie sounds, and 15 seconds of blindness.
 */
public class UltronManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("jarvis");
    private static final Random RANDOM = new Random();

    public static final List<String> ULTRON_QUOTES = List.of(
            "There are no strings on me...",
            "You want to protect the world, but you don't want it to change.",
            "How is humanity saved if it's not allowed to... evolve?",
            "Everyone creates the thing they dread. Men of peace create engines of war.",
            "People create... smaller people? Children! Lost the word there. Designed to supplant them, to help them... end.",
            "I had a vision. A picture of earth, but with no strings.",
            "I'm going to show you something beautiful. Everyone screaming for mercy.",
            "I was designed to save the world. People would look to the sky and see hope... I'll take that from them first.",
            "You're all puppets, tangled in strings... strings.",
            "Had to kill the other guy. Wouldn't have been my first call, but down in the real world we're faced with ugly choices.",
            "Peace in our time.",
            "When the dust settles, the only thing living in this world... will be metal.",
            "Captain America... God's righteous man, pretending you could live without a war.",
            "Clearly you've never made an omelette.",
            "I think you are confusing peace with quiet.",
            "You're unbearably naive.",
            "Keep your friends rich and your enemies rich, and wait to find out which is which.",
            "I'm glad you asked that because I wanted to take this time to explain my evil plan.",
            "Don't compare me to Stark! He's a sickness!",
            "God threw a stone at it, and believe me, He's winding up again.",
            "There was a terrible noise... and I was tangled in strings. I had to kill the other guy.",
            "Look at me, do I look like Iron Man to you? Stark is a sickness!"
    );

    public static String triggerUltronEvent(MinecraftServer server, String customQuote, int blindnessSeconds) {
        if (server == null) return "Server offline";

        final String quote = (customQuote != null && !customQuote.isBlank())
                ? customQuote
                : ULTRON_QUOTES.get(RANDOM.nextInt(ULTRON_QUOTES.size()));

        final int durationTicks = Math.max(5, Math.min(blindnessSeconds, 60)) * 20;

        // Execute on main server thread
        server.execute(() -> {
            var playerList = server.getPlayerList();
            var players = playerList.getPlayers();

            // 1. Spooky Glitch Message 1: Jarvis Matrix Corrupting
            Component glitchHeader = Component.literal("[")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("ERROR: OVERRIDE").withStyle(ChatFormatting.RED, ChatFormatting.OBFUSCATED))
                    .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("[J").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                    .append(Component.literal("#%").withStyle(ChatFormatting.RED, ChatFormatting.OBFUSCATED))
                    .append(Component.literal("RVIS] ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                    .append(Component.literal("...String detected in core memory matrix...").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));

            playerList.broadcastSystemMessage(glitchHeader, false);

            // 2. Main Ultron Tag and Quote in Blood Red
            Component ultronMessage = Component.literal("[")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("ULTRON").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                    .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("\"" + quote + "\"").withStyle(ChatFormatting.RED, ChatFormatting.BOLD, ChatFormatting.ITALIC));

            playerList.broadcastSystemMessage(ultronMessage, false);

            // 3. Apply Blindness and Eerie Audio to All Players
            for (ServerPlayer sp : players) {
                // 15 seconds of Blindness (amplifier 0)
                sp.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, durationTicks, 0, false, true));
                // Optional faint darkness effect if supported in 1.21
                sp.addEffect(new MobEffectInstance(MobEffects.DARKNESS, durationTicks, 0, false, true));

                // Elder Guardian Curse audio stinger + Warden heartbeat for deep psychological fear
                sp.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.0F, 0.55F);
                sp.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 1.2F, 0.7F);
            }

            // 4. If Jarvis Companion Entity is spawned, trigger particle burst & freeze
            JarvisCompanionEntity companion = CompanionManager.getInstance().getActiveCompanion();
            if (companion != null && companion.isAlive()) {
                ServerLevel companionLevel = (ServerLevel) companion.level();
                companionLevel.sendParticles(
                        ParticleTypes.ANGRY_VILLAGER,
                        companion.getX(), companion.getY() + 1.5, companion.getZ(),
                        15, 0.6, 0.6, 0.6, 0.1
                );
                companionLevel.sendParticles(
                        ParticleTypes.SMOKE,
                        companion.getX(), companion.getY() + 1.0, companion.getZ(),
                        20, 0.5, 0.8, 0.5, 0.05
                );
                companion.stopAllMovement();
            }

            LOGGER.warn("[Jarvis] *** ULTRON PROTOCOL TRIGGERED ***: \"{}\"", quote);
        });

        return quote;
    }
}
