package com.bymatej.siege.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The enemy roster of a siege.
 *
 * <p>Every type unlocks in a fixed wave, so a match escalates gradually
 * instead of throwing the same three mobs at the players from wave 1. A
 * per-type cap keeps the late-game specials rare.</p>
 *
 * <p>The enum holds no Bukkit constant, so its logic stays unit testable
 * without a running server; see {@link MobTypes} for the Bukkit types.</p>
 */
public enum MobType {
    ZOMBIE("zombie", "Zombie", 1, 0, false, true),
    SKELETON("skeleton", "Skeleton", 1, 0, false, true),
    PILLAGER("pillager", "Pillager", 2, 0, false, true),
    VINDICATOR("vindicator", "Vindicator", 3, 0, false, true),
    WITHER_SKELETON("wither_skeleton", "Wither Skeleton", 4, 0, false, true),
    PIGLIN_BRUTE("piglin_brute", "Piglin Brute", 5, 0, false, true),
    BLAZE("blaze", "Blaze", 6, 0, false, true),
    CREEPER("creeper", "Creeper", 7, 0, false, true),
    /** Only in the final wave and at most one per wave: a mini-boss. */
    RAVAGER("ravager", "Ravager", 1, 1, true, false);

    private final String key;
    private final String display;
    private final int minWave;
    /** Maximum number of this type per wave; 0 means no limit. */
    private final int cap;
    private final boolean lastWaveOnly;
    private final boolean defaultEnabled;

    MobType(String key, String display, int minWave, int cap, boolean lastWaveOnly,
            boolean defaultEnabled) {
        this.key = key;
        this.display = display;
        this.minWave = minWave;
        this.cap = cap;
        this.lastWaveOnly = lastWaveOnly;
        this.defaultEnabled = defaultEnabled;
    }

    public String key() { return key; }
    public String display() { return display; }
    public int minWave() { return minWave; }
    public int cap() { return cap; }
    public boolean lastWaveOnly() { return lastWaveOnly; }
    public boolean defaultEnabled() { return defaultEnabled; }

    /** True when this type may spawn in the given wave of the given match. */
    public boolean available(int wave, int totalWaves) {
        if (wave < 1 || totalWaves < 1) return false;
        if (lastWaveOnly) return wave >= totalWaves;
        return wave >= minWave;
    }

    /** The enabled types that may spawn in this wave, in roster order. */
    public static List<MobType> activeFor(int wave, int totalWaves, Set<MobType> enabled) {
        List<MobType> active = new ArrayList<>();
        for (MobType type : values()) {
            if (enabled.contains(type) && type.available(wave, totalWaves)) active.add(type);
        }
        return active;
    }

    /**
     * The type of the n-th enemy of a wave. The types take turns, so a wave is
     * a mix instead of a single mob type; a type that already reached its cap
     * is skipped. Returns null when every type is capped.
     */
    public static MobType pick(List<MobType> active, int index, Map<MobType, Integer> spawned) {
        int size = active.size();
        if (size == 0) return null;
        for (int i = 0; i < size; i++) {
            MobType type = active.get((index + i) % size);
            int cap = type.cap();
            if (cap > 0 && spawned.getOrDefault(type, 0) >= cap) continue;
            return type;
        }
        return null;
    }
}
