package com.bymatej.siege.ai;

/**
 * The pure part of the mining times: how long a break takes and what a break
 * costs in the breach planner. No Bukkit types, so the rules are unit tested.
 */
public final class MiningTimes {
    /** The price of a break in the breach planner, at most. */
    public static final double MAX_SEARCH_COST = 4.0;

    private MiningTimes() { }

    /**
     * The ticks a break takes. The right tool is much faster than the wrong
     * one, like in vanilla.
     *
     * @param hardness the hardness of the block
     * @param destroySpeed the destroy speed of the block with the tool in hand
     * @param correctTool true when the tool is the one the block wants
     */
    public static int ticks(double hardness, double destroySpeed, boolean correctTool) {
        double h = Math.max(0.1, hardness);
        double s = Math.max(0.1, destroySpeed);
        return (int) Math.ceil(h * (correctTool ? 30 : 100) / s);
    }

    /**
     * The ticks a desperate break takes. The right tool is still the fastest
     * way through, but bare hands get through as well: a mob that has been
     * stuck for a long time tears the wall down instead of waiting.
     */
    public static int desperateTicks(double hardness, double destroySpeed, boolean correctTool) {
        double h = Math.max(0.1, hardness);
        double s = Math.max(0.1, destroySpeed);
        return (int) Math.ceil(h * (correctTool ? 30 : 60) / s);
    }

    /**
     * The price of a break in the breach planner. It is capped on purpose:
     * the planner only picks the place to dig, the digging itself is timed
     * when the mob breaks the block. A high price would make the planner walk
     * around a wall instead of digging through it.
     */
    public static double searchCost(double ticks) {
        return Math.min(ticks / 12.0, MAX_SEARCH_COST);
    }
}
