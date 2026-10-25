package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LadderClimbTest {
    /** The mob stands two blocks south of the ladder column at x=0, z=0. */
    private static final double LADDER_X = 0.5;
    private static final double LADDER_Z = 0.5;
    private static final int TOP_Y = 70;

    @Test void aMobWalksTowardsTheLadderBeforeItClimbs() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.APPROACH,
                0.5, 64, 3.5, LADDER_X, LADDER_Z, TOP_Y, 0.5, 0.5);
        assertEquals(LadderClimb.Phase.APPROACH, move.phase());
        assertEquals(0.0, move.vy(), 0.0001, "the mob must not jump while it walks");
        assertTrue(move.vz() < 0, "the mob has to move towards the ladder");
        assertTrue(Math.abs(move.vz()) <= LadderClimb.HORIZONTAL_SPEED + 0.0001);
    }

    @Test void aMobAtTheLadderStartsToClimb() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.APPROACH,
                0.5, 64, 0.6, LADDER_X, LADDER_Z, TOP_Y, 0.5, 0.5);
        assertEquals(LadderClimb.Phase.CLIMB, move.phase());
        assertEquals(LadderClimb.CLIMB_SPEED, move.vy(), 0.0001);
    }

    @Test void aClimbingMobRisesUntilItIsAboveTheTopLadderBlock() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.CLIMB,
                0.5, 69, 0.5, LADDER_X, LADDER_Z, TOP_Y, 0.5, 0.5);
        assertEquals(LadderClimb.Phase.CLIMB, move.phase());
        assertEquals(LadderClimb.CLIMB_SPEED, move.vy(), 0.0001);
    }

    @Test void aMobAboveTheTopLadderBlockStepsOff() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.CLIMB,
                0.5, 71, 0.5, LADDER_X, LADDER_Z, TOP_Y, 3.5, 0.5);
        assertEquals(LadderClimb.Phase.STEP_OFF, move.phase());
        assertEquals(0.0, move.vx(), 0.0001);
    }

    @Test void aMobStepsOffTowardsThePlatform() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.STEP_OFF,
                0.5, 71, 0.5, LADDER_X, LADDER_Z, TOP_Y, 3.5, 0.5);
        assertEquals(LadderClimb.Phase.STEP_OFF, move.phase());
        assertTrue(move.vx() > 0, "the mob has to move onto the platform");
        assertTrue(Math.abs(move.vx()) <= LadderClimb.HORIZONTAL_SPEED + 0.0001);
    }

    @Test void aMobOnThePlatformIsDone() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.STEP_OFF,
                3.5, 71, 0.5, LADDER_X, LADDER_Z, TOP_Y, 3.5, 0.5);
        assertEquals(LadderClimb.Phase.DONE, move.phase());
        assertEquals(0.0, move.vx(), 0.0001);
        assertEquals(0.0, move.vy(), 0.0001);
    }

    @Test void aMobThatSlippedBackDownClimbsAgain() {
        LadderClimb.Move move = LadderClimb.move(LadderClimb.Phase.STEP_OFF,
                0.5, 66, 0.5, LADDER_X, LADDER_Z, TOP_Y, 3.5, 0.5);
        assertEquals(LadderClimb.Phase.CLIMB, move.phase());
        assertEquals(LadderClimb.CLIMB_SPEED, move.vy(), 0.0001);
    }
}
