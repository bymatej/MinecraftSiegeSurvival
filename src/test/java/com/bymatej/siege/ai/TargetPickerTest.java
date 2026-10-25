package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TargetPickerTest {
    private static TargetPicker.Candidate player(String id, double distance, boolean visible) {
        return new TargetPicker.Candidate(id, distance * distance, visible, true, true);
    }

    @Test void theNearestVisiblePlayerIsPicked() {
        List<TargetPicker.Candidate> candidates = List.of(
                player("far", 20, true), player("near", 5, true));
        assertEquals("near", TargetPicker.pick(null, 0, candidates, 24, 32, false));
    }

    @Test void aHiddenPlayerIsPickedToo() {
        // The player is behind a wall: the mob has to dig him out.
        List<TargetPicker.Candidate> candidates = List.of(player("hidden", 8, false));
        assertEquals("hidden", TargetPicker.pick(null, 0, candidates, 24, 32, false));
    }

    @Test void aVisiblePlayerWinsOverAHiddenOne() {
        List<TargetPicker.Candidate> candidates = List.of(
                player("hidden", 3, false), player("visible", 10, true));
        assertEquals("visible", TargetPicker.pick(null, 0, candidates, 24, 32, false));
    }

    @Test void withoutALineOfSightOnlyVisiblePlayersCount() {
        List<TargetPicker.Candidate> candidates = List.of(player("hidden", 8, false));
        assertNull(TargetPicker.pick(null, 0, candidates, 24, 32, true));
    }

    @Test void aPlayerOutsideTheDetectionRadiusIsNoticed() {
        List<TargetPicker.Candidate> candidates = List.of(player("far", 40, true));
        assertNull(TargetPicker.pick(null, 0, candidates, 24, 32, false));
    }

    @Test void theCurrentTargetIsKeptWhileItIsInReach() {
        List<TargetPicker.Candidate> candidates = List.of(
                player("current", 20, false), player("other", 3, true));
        assertEquals("current", TargetPicker.pick("current", 400, candidates, 24, 32, false));
    }

    @Test void aTargetOutOfReachIsReplaced() {
        List<TargetPicker.Candidate> candidates = List.of(
                player("current", 40, false), player("other", 3, true));
        assertEquals("other", TargetPicker.pick("current", 1600, candidates, 24, 32, false));
    }

    @Test void withoutALineOfSightTheCurrentTargetIsDroppedToo() {
        // Only visible players count - also for the player the mob already hunts.
        List<TargetPicker.Candidate> candidates = List.of(
                player("current", 20, false), player("other", 3, true));
        assertEquals("other", TargetPicker.pick("current", 400, candidates, 24, 32, true));
    }

    @Test void aPlayerInAnotherWorldIsNeverPicked() {
        List<TargetPicker.Candidate> candidates = List.of(
                new TargetPicker.Candidate("other", 4, false, true, false));
        assertNull(TargetPicker.pick(null, 0, candidates, 24, 32, false));
    }

    @Test void withoutAnyPlayerTheMobGoesForTheAnchor() {
        assertNull(TargetPicker.pick(null, 0, List.of(), 24, 32, false));
    }
}
