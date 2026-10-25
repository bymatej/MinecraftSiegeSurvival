package com.bymatej.siege.ai;

import java.util.List;

/**
 * Decides which player a mob hunts. The mobs hunt the players; the anchor is
 * only the fallback when no player is around. A player the mob cannot see is
 * taken as well, so hiding in a box is no protection. Pure logic without
 * Bukkit types so that it can be unit tested.
 */
public final class TargetPicker {
    private TargetPicker() { }

    /** One player the mob could hunt. */
    public record Candidate(String id, double distanceSquared, boolean visible,
                            boolean alive, boolean sameWorld) { }

    /**
     * Picks the player the mob hunts.
     *
     * @param currentId the id of the target the mob already has, null when it
     *                  has none
     * @param currentDistanceSquared the distance to that target
     * @param candidates every player in the world
     * @param detectionRadius how far a player is noticed
     * @param pursuitRadius how far the mob keeps its target
     * @param requireLineOfSight true when only a visible player may be taken
     * @return the id of the player to hunt, null when the mob goes for the anchor
     */
    public static String pick(String currentId, double currentDistanceSquared,
                              List<Candidate> candidates, double detectionRadius,
                              double pursuitRadius, boolean requireLineOfSight) {
        if (currentId != null) {
            for (Candidate candidate : candidates) {
                if (!candidate.id().equals(currentId)) continue;
                // The target is still in reach: the mob keeps hunting it, even
                // when a wall is in the way - unless only visible players count.
                if (candidate.alive() && candidate.sameWorld()
                        && candidate.distanceSquared() <= pursuitRadius * pursuitRadius
                        && (!requireLineOfSight || candidate.visible())) {
                    return currentId;
                }
                break;
            }
        }
        // Otherwise the nearest player is taken; a visible one is preferred.
        double radius = detectionRadius * detectionRadius;
        String nearest = null;
        double nearestDistance = radius;
        String visible = null;
        double visibleDistance = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (!candidate.alive() || !candidate.sameWorld()) continue;
            if (candidate.visible() && candidate.distanceSquared() <= radius
                    && candidate.distanceSquared() < visibleDistance) {
                visible = candidate.id();
                visibleDistance = candidate.distanceSquared();
            }
            if (candidate.distanceSquared() < nearestDistance) {
                nearest = candidate.id();
                nearestDistance = candidate.distanceSquared();
            }
        }
        if (visible != null) return visible;
        return requireLineOfSight ? null : nearest;
    }
}
