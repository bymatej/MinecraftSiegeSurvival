package com.bymatej.siege.game;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * The Bukkit types behind the roster entries.
 *
 * <p>They live apart from {@link MobType} on purpose: the roster logic is
 * plain Java and can be unit tested without a running server, while these
 * constants need the Bukkit registries.</p>
 */
public final class MobTypes {
    private MobTypes() { }

    public static EntityType entityType(MobType type) {
        return switch (type) {
            case ZOMBIE -> EntityType.ZOMBIE;
            case SKELETON -> EntityType.SKELETON;
            case PILLAGER -> EntityType.PILLAGER;
            case VINDICATOR -> EntityType.VINDICATOR;
            case WITHER_SKELETON -> EntityType.WITHER_SKELETON;
            case PIGLIN_BRUTE -> EntityType.PIGLIN_BRUTE;
            case BLAZE -> EntityType.BLAZE;
            case CREEPER -> EntityType.CREEPER;
            case RAVAGER -> EntityType.RAVAGER;
        };
    }

    /** The icon of the type in the enemy menu. */
    public static Material icon(MobType type) {
        return switch (type) {
            case ZOMBIE -> Material.ZOMBIE_SPAWN_EGG;
            case SKELETON -> Material.SKELETON_SPAWN_EGG;
            case PILLAGER -> Material.PILLAGER_SPAWN_EGG;
            case VINDICATOR -> Material.VINDICATOR_SPAWN_EGG;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SPAWN_EGG;
            case PIGLIN_BRUTE -> Material.PIGLIN_BRUTE_SPAWN_EGG;
            case BLAZE -> Material.BLAZE_SPAWN_EGG;
            case CREEPER -> Material.CREEPER_SPAWN_EGG;
            case RAVAGER -> Material.RAVAGER_SPAWN_EGG;
        };
    }
}
