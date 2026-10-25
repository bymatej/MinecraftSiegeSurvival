package com.bymatej.siege.ai;

/**
 * Tracks how close a mob has come to its goal. A mob that jitters against a
 * wall moves a fraction of a block per control tick, so "did the position
 * change" is no proof that it is not stuck - only the distance to the goal
 * tells. Pure logic without Bukkit types so that it can be unit tested.
 */
public final class ProgressTracker {
    private double bestDistance = Double.MAX_VALUE;
    private int stuckTicks;

    /**
     * Records the current distance to the goal.
     *
     * @param distance the current distance to the goal
     * @param epsilon  how much closer the mob has to get to count as progress
     * @return true when the mob made progress
     */
    public boolean update(double distance, double epsilon) {
        if (distance < bestDistance - epsilon) {
            bestDistance = distance;
            stuckTicks = 0;
            return true;
        }
        stuckTicks++;
        return false;
    }

    /** Control ticks without progress. */
    public int stuckTicks() {
        return stuckTicks;
    }

    /** The closest distance to the goal so far. */
    public double bestDistance() {
        return bestDistance;
    }

    /** Forgets the stall, for example while the mob fights a player. */
    public void reset() {
        stuckTicks = 0;
    }

    /**
     * Starts a new goal: the old distance is meaningless, because the mob now
     * moves towards a different point.
     *
     * @param distance the current distance to the new goal
     */
    public void resetGoal(double distance) {
        bestDistance = distance;
        stuckTicks = 0;
    }
}
