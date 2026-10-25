package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressTrackerTest {
    private static final double EPSILON = 0.6;

    @Test void theFirstMeasurementIsAlwaysProgress() {
        ProgressTracker tracker = new ProgressTracker();
        assertTrue(tracker.update(10, EPSILON));
        assertEquals(0, tracker.stuckTicks());
        assertEquals(10, tracker.bestDistance(), 0.0001);
    }

    @Test void aMobThatJittersAgainstAWallCountsAsStuck() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.update(10, EPSILON);
        // The mob wiggles: the distance changes a little, but it never gets closer.
        tracker.update(10.05, EPSILON);
        tracker.update(9.98, EPSILON);
        tracker.update(10.02, EPSILON);
        assertEquals(3, tracker.stuckTicks());
    }

    @Test void realProgressClearsTheStall() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.update(10, EPSILON);
        tracker.update(10, EPSILON);
        tracker.update(10, EPSILON);
        assertEquals(2, tracker.stuckTicks());
        // Half a block is not enough, a full step is.
        tracker.update(9.7, EPSILON);
        assertEquals(3, tracker.stuckTicks());
        tracker.update(8, EPSILON);
        assertEquals(0, tracker.stuckTicks());
        assertEquals(8, tracker.bestDistance(), 0.0001);
    }

    @Test void movingAwayDoesNotResetTheStall() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.update(10, EPSILON);
        tracker.update(12, EPSILON);
        assertEquals(1, tracker.stuckTicks());
        assertEquals(10, tracker.bestDistance(), 0.0001);
    }

    @Test void aResetForgetsTheStallButKeepsTheBestDistance() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.update(10, EPSILON);
        tracker.update(10, EPSILON);
        tracker.reset();
        assertEquals(0, tracker.stuckTicks());
        assertEquals(10, tracker.bestDistance(), 0.0001);
        // After the reset the mob is still measured against its best distance.
        tracker.update(10.2, EPSILON);
        assertEquals(1, tracker.stuckTicks());
    }

    @Test void aNewGoalStartsFromScratch() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.update(60, EPSILON);
        tracker.update(60, EPSILON);
        assertEquals(1, tracker.stuckTicks());
        // The mob now hunts a player 5 blocks away: the old distance is gone.
        tracker.resetGoal(5);
        assertEquals(0, tracker.stuckTicks());
        assertEquals(5, tracker.bestDistance(), 0.0001);
        assertTrue(tracker.update(4, EPSILON));
    }
}
