package com.bymatej.siege.ai;

/**
 * Decides how a mob gets past the ground in front of it. Pure logic without
 * Bukkit types so that it can be unit tested.
 */
public final class TerrainTools {
    /** How the mob deals with the ground in front of it. */
    public enum Action {
        /** Nothing to do: the way is open or a wall that has to be mined. */
        NONE,
        /** The ground is one block higher: the mob jumps the step. */
        STEP_UP,
        /** A one block gap: the mob jumps over it. */
        GAP,
        /** A wider or deeper hole: the mob builds a floor across it. */
        BRIDGE
    }

    private TerrainTools() { }

    /**
     * The number of missing floor cells in front of a mob.
     *
     * @param floors the floor cells in front of the mob, true when there is floor
     * @param limit how many cells are looked at
     * @return the number of cells without a floor before the ground continues
     */
    public static int gapWidth(boolean[] floors, int limit) {
        int width = 0;
        int max = Math.min(limit, floors.length);
        for (int i = 0; i < max; i++) {
            if (floors[i]) break;
            width++;
        }
        return width;
    }

    /**
     * The action a mob takes for a hole in front of it.
     *
     * @param gapWidth how many cells are missing
     * @param dropDepth how far the ground drops: 0 is the same level, 1 is a
     *                  step the mob can jump out of again, 2 and more is a
     *                  hole it would be stuck in
     * @return the action the mob takes
     */
    public static Action decide(int gapWidth, int dropDepth) {
        if (gapWidth <= 0) return Action.NONE;
        // A narrow, shallow gap can be jumped, a deep hole cannot: the mob
        // would fall in and never get out, so it builds a floor over it.
        if (gapWidth == 1 && dropDepth <= 1) return Action.GAP;
        return Action.BRIDGE;
    }
}
