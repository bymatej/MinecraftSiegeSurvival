package com.bymatej.siege.ai;

/**
 * Pure decision logic for the ladder climb of a siege mob. Paper's A* treats a
 * ladder as a wall, so a mob whose anchor is above it has to be steered up
 * the ladder by the plugin. This class only decides where the mob has to move;
 * the Bukkit side applies the velocity.
 */
public final class LadderClimb {
    /** What the mob does next. */
    public enum Phase { APPROACH, CLIMB, STEP_OFF, DONE }

    /** The movement the mob should make in this tick. */
    public record Move(Phase phase, double vx, double vy, double vz) { }

    /** How close the mob has to be to the ladder column before it climbs. */
    public static final double REACH = 0.65;
    /** Horizontal speed while approaching, climbing and stepping off. */
    public static final double HORIZONTAL_SPEED = 0.18;
    /** Upward speed while the mob climbs. */
    public static final double CLIMB_SPEED = 0.25;

    private LadderClimb() { }

    /**
     * @param phase the phase the mob is in
     * @param mobX the X of the mob's feet
     * @param mobY the Y of the mob's feet
     * @param mobZ the Z of the mob's feet
     * @param ladderX the X of the ladder column (the block the ladder is in)
     * @param ladderZ the Z of the ladder column
     * @param ladderTopY the Y of the highest ladder block of the run
     * @param exitX the X of the platform the mob steps onto at the top
     * @param exitZ the Z of that platform
     */
    public static Move move(Phase phase, double mobX, double mobY, double mobZ,
                            double ladderX, double ladderZ, int ladderTopY,
                            double exitX, double exitZ) {
        double dx = ladderX - mobX;
        double dz = ladderZ - mobZ;
        double distance = Math.hypot(dx, dz);
        return switch (phase) {
            case APPROACH -> distance > REACH
                    ? new Move(Phase.APPROACH, dx / distance * HORIZONTAL_SPEED, 0.0,
                            dz / distance * HORIZONTAL_SPEED)
                    : new Move(Phase.CLIMB, clamp(dx * 0.5), CLIMB_SPEED, clamp(dz * 0.5));
            case CLIMB -> mobY >= ladderTopY + 1
                    ? new Move(Phase.STEP_OFF, 0.0, 0.0, 0.0)
                    : new Move(Phase.CLIMB, clamp(dx * 0.5), CLIMB_SPEED, clamp(dz * 0.5));
            case STEP_OFF -> {
                if (mobY < ladderTopY) {
                    // The mob slipped back down the ladder: climb again.
                    yield new Move(Phase.CLIMB, clamp(dx * 0.5), CLIMB_SPEED, clamp(dz * 0.5));
                }
                double ex = exitX - mobX;
                double ez = exitZ - mobZ;
                double exitDistance = Math.hypot(ex, ez);
                yield exitDistance < 0.2
                        ? new Move(Phase.DONE, 0.0, 0.0, 0.0)
                        : new Move(Phase.STEP_OFF, ex / exitDistance * HORIZONTAL_SPEED, 0.05,
                                ez / exitDistance * HORIZONTAL_SPEED);
            }
            case DONE -> new Move(Phase.DONE, 0.0, 0.0, 0.0);
        };
    }

    private static double clamp(double value) {
        return Math.max(-HORIZONTAL_SPEED, Math.min(HORIZONTAL_SPEED, value));
    }
}
