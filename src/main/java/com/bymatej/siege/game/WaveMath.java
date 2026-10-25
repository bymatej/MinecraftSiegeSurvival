package com.bymatej.siege.game;

/** Pure deterministic scaling calculations; no Bukkit dependencies. */
public final class WaveMath {
    private WaveMath() { }

    public static double healthMultiplier(int wave, int total) {
        if (total < 1 || wave < 1 || wave > total) throw new IllegalArgumentException("invalid wave");
        if (total == 1) return 0.30;
        return Math.min(1.50, 0.30 + 1.20 * (wave - 1.0) / (total - 1.0));
    }

    /**
     * Enemy count multiplier by player count: one player keeps the base count,
     * two players 1.3x, three 1.5x, four 1.7x and so on - 0.2 more per extra
     * player. The count is rounded down for an even number of players and up
     * for an odd one.
     */
    public static double playerMultiplier(int players) {
        if (players < 1) throw new IllegalArgumentException("invalid player count");
        if (players == 1) return 1.0;
        return 1.3 + 0.2 * (players - 2);
    }

    /**
     * The factor the break time of a block is multiplied with. 1.0 is the
     * normal, fair speed; from the last quarter of the waves the mobs break
     * through their walls up to lateSpeed times faster.
     *
     * @param wave the current wave
     * @param total the number of waves of the match
     * @param lateSpeed how much faster the mobs break in the last quarter
     */
    public static double breakTimeFactor(int wave, int total, double lateSpeed) {
        if (total < 1 || wave < 1 || wave > total) throw new IllegalArgumentException("invalid wave");
        if (total == 1) return 1.0;
        double speed = Math.max(1.0, lateSpeed);
        double progress = (wave - 1.0) / (total - 1.0);
        // The ramp starts in the last quarter of the waves.
        double ramp = Math.max(0.0, (progress - 0.75) / 0.25);
        return 1.0 / (1.0 + (speed - 1.0) * Math.min(1.0, ramp));
    }

    public static int enemies(int wave, int players, int cap, double base, double growth) {
        if (wave < 1 || players < 1 || cap < 1 || base < 0 || growth < 0)
            throw new IllegalArgumentException("invalid wave settings");
        double raw = (base + growth * (wave - 1)) * playerMultiplier(players);
        int count = players % 2 == 0 ? (int) Math.floor(raw) : (int) Math.ceil(raw);
        return Math.max(1, Math.min(cap, count));
    }

    public static boolean outside(double x, double z, double anchorX, double anchorZ, double radius) {
        double dx = x - anchorX, dz = z - anchorZ;
        return radius < 1 || dx * dx + dz * dz > radius * radius;
    }

    /**
     * True when the chunk (chunkX, chunkZ) can contain a spawn point of the
     * annulus [min, max] blocks around the anchor. Every such chunk has to be
     * kept loaded, otherwise the enemy that spawns there is unloaded within
     * seconds and drops out of the wave bookkeeping.
     */
    public static boolean chunkIntersectsAnnulus(int chunkX, int chunkZ, int anchorX, int anchorZ,
                                                  int min, int max) {
        int minX = chunkX * 16, maxX = minX + 15;
        int minZ = chunkZ * 16, maxZ = minZ + 15;
        double nearX = anchorX < minX ? minX - anchorX : (anchorX > maxX ? anchorX - maxX : 0);
        double nearZ = anchorZ < minZ ? minZ - anchorZ : (anchorZ > maxZ ? anchorZ - maxZ : 0);
        double farthestX = Math.max(Math.abs(anchorX - minX), Math.abs(anchorX - maxX));
        double farthestZ = Math.max(Math.abs(anchorZ - minZ), Math.abs(anchorZ - maxZ));
        return Math.hypot(nearX, nearZ) <= max && Math.hypot(farthestX, farthestZ) >= min;
    }

    /**
     * True when the chunk (chunkX, chunkZ) can hold a cell inside the circle
     * around the anchor. The arena has to stay loaded: a mob in an unloaded
     * chunk stops moving and cannot be found.
     */
    public static boolean chunkIntersectsCircle(int chunkX, int chunkZ, int anchorX, int anchorZ,
                                                int radius) {
        if (radius < 1) return false;
        int minX = chunkX * 16, maxX = minX + 15;
        int minZ = chunkZ * 16, maxZ = minZ + 15;
        double nearX = anchorX < minX ? minX - anchorX : (anchorX > maxX ? anchorX - maxX : 0);
        double nearZ = anchorZ < minZ ? minZ - anchorZ : (anchorZ > maxZ ? anchorZ - maxZ : 0);
        return nearX * nearX + nearZ * nearZ <= (double) radius * radius;
    }
}
