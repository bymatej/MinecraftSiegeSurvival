package com.bymatej.siege.game;

/** Pure deterministic scaling calculations; no Bukkit dependencies. */
public final class WaveMath {
    private WaveMath() { }

    public static double healthMultiplier(int wave, int total) {
        if (total < 1 || wave < 1 || wave > total) throw new IllegalArgumentException("invalid wave");
        if (total == 1) return 0.10;
        return Math.min(1.50, 0.10 + 1.40 * (wave - 1.0) / (total - 1.0));
    }

    public static int enemies(int wave, int players, int cap, double base, double growth, double scale) {
        if (wave < 1 || players < 1 || cap < 1 || base < 0 || growth < 0 || scale < 0)
            throw new IllegalArgumentException("invalid wave settings");
        return Math.max(1, Math.min(cap, (int) Math.ceil(
                (base + growth * (wave - 1)) * (1 + scale * (players - 1)))));
    }

    public static boolean outside(double x, double z, double anchorX, double anchorZ, double radius) {
        double dx = x - anchorX, dz = z - anchorZ;
        return radius < 1 || dx * dx + dz * dz > radius * radius;
    }
}
