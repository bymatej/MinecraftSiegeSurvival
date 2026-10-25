package com.bymatej.siege.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveMathTest {
    @Test void healthScalesToLimits() {
        assertEquals(0.30, WaveMath.healthMultiplier(1, 25), 0.000001);
        assertEquals(1.50, WaveMath.healthMultiplier(25, 25), 0.000001);
        assertEquals(0.30, WaveMath.healthMultiplier(1, 1), 0.000001);
        assertEquals(0.90, WaveMath.healthMultiplier(13, 25), 0.000001);
        assertThrows(IllegalArgumentException.class, () -> WaveMath.healthMultiplier(0, 25));
    }

    @Test void mobCountsAreCapped() {
        assertEquals(5, WaveMath.enemies(1, 1, 120, 5, 1.3));
        assertEquals(37, WaveMath.enemies(25, 1, 120, 5, 1.3));
        assertEquals(120, WaveMath.enemies(100, 4, 120, 5, 1.3));
    }

    @Test void theBreachGetsFasterInTheLastQuarter() {
        // The early waves keep the fair, slow breaching.
        assertEquals(1.0, WaveMath.breakTimeFactor(1, 25, 2.0), 0.000001);
        assertEquals(1.0, WaveMath.breakTimeFactor(19, 25, 2.0), 0.000001);
        // From wave 20 on the mobs break through twice as fast.
        assertEquals(0.5, WaveMath.breakTimeFactor(25, 25, 2.0), 0.000001);
        assertEquals(1.0, WaveMath.breakTimeFactor(1, 1, 2.0), 0.000001);
        assertEquals(1.0, WaveMath.breakTimeFactor(5, 25, 1.0), 0.000001);
        assertThrows(IllegalArgumentException.class, () -> WaveMath.breakTimeFactor(0, 25, 2.0));
    }

    @Test void theArenaChunksAreTheOnesInsideTheCircle() {
        // The chunk of the anchor is always inside.
        assertTrue(WaveMath.chunkIntersectsCircle(0, 0, 0, 0, 60));
        // A chunk that touches the circle counts, a far one does not.
        assertTrue(WaveMath.chunkIntersectsCircle(3, 0, 0, 0, 60));
        assertFalse(WaveMath.chunkIntersectsCircle(5, 0, 0, 0, 60));
        assertFalse(WaveMath.chunkIntersectsCircle(0, 0, 0, 0, 0));
    }

    @Test void playerMultiplierGrowsByTwoTenths() {
        assertEquals(1.0, WaveMath.playerMultiplier(1), 0.000001);
        assertEquals(1.3, WaveMath.playerMultiplier(2), 0.000001);
        assertEquals(1.5, WaveMath.playerMultiplier(3), 0.000001);
        assertEquals(1.7, WaveMath.playerMultiplier(4), 0.000001);
        assertEquals(1.9, WaveMath.playerMultiplier(5), 0.000001);
        assertEquals(2.1, WaveMath.playerMultiplier(6), 0.000001);
        assertThrows(IllegalArgumentException.class, () -> WaveMath.playerMultiplier(0));
    }

    /** Even player counts round down, odd ones round up. */
    @Test void mobCountRoundsByPlayerParity() {
        // wave 1: 5 enemies * 1.3 = 6.5 -> down for 2 players
        assertEquals(6, WaveMath.enemies(1, 2, 120, 5, 1.3));
        // wave 1: 5 enemies * 1.5 = 7.5 -> up for 3 players
        assertEquals(8, WaveMath.enemies(1, 3, 120, 5, 1.3));
        // wave 1: 5 enemies * 1.7 = 8.5 -> down for 4 players
        assertEquals(8, WaveMath.enemies(1, 4, 120, 5, 1.3));
        // wave 1: 5 enemies * 1.9 = 9.5 -> up for 5 players
        assertEquals(10, WaveMath.enemies(1, 5, 120, 5, 1.3));
        // one player keeps the base count
        assertEquals(5, WaveMath.enemies(1, 1, 120, 5, 1.3));
    }

    @Test void circularBoundaryUsesHorizontalDistance() {
        assertFalse(WaveMath.outside(100, 0, 0, 0, 100));
        assertTrue(WaveMath.outside(101, 0, 0, 0, 100));
        assertTrue(WaveMath.outside(80, 80, 0, 0, 100));
    }

    @Test void spawnChunkCoverageFollowsTheSpawnAnnulus() {
        int min = 40, max = 72;
        int anchorX = 100, anchorZ = 100;
        int anchorChunkX = anchorX >> 4, anchorChunkZ = anchorZ >> 4;
        // The anchor chunk itself is not part of the spawn annulus.
        assertFalse(WaveMath.chunkIntersectsAnnulus(anchorChunkX, anchorChunkZ, anchorX, anchorZ, min, max));
        // A chunk 40 blocks away can hold a spawn point.
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 2, anchorChunkZ, anchorX, anchorZ, min, max));
        // A chunk 72 blocks away can hold a spawn point.
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 4, anchorChunkZ, anchorX, anchorZ, min, max));
        // Far beyond the spawn radius.
        assertFalse(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 8, anchorChunkZ, anchorX, anchorZ, min, max));
        // A chunk that only touches the annulus from the inside is covered too.
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 2, anchorChunkZ + 2, anchorX, anchorZ, min, max));
    }

    @Test void spawnChunkCoverageWorksForNegativeCoordinates() {
        int min = 40, max = 72;
        int anchorX = -100, anchorZ = -100;
        int anchorChunkX = anchorX >> 4, anchorChunkZ = anchorZ >> 4;
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX - 3, anchorChunkZ, anchorX, anchorZ, min, max));
        assertFalse(WaveMath.chunkIntersectsAnnulus(anchorChunkX - 8, anchorChunkZ, anchorX, anchorZ, min, max));
    }

    /** The whole arena is held loaded, not only the spawn annulus. */
    @Test void arenaCoverageIncludesEveryChunkInsideTheRadius() {
        int radius = 60;
        int anchorX = 100, anchorZ = 100;
        int anchorChunkX = anchorX >> 4, anchorChunkZ = anchorZ >> 4;
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX, anchorChunkZ, anchorX, anchorZ, 0, radius));
        assertTrue(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 3, anchorChunkZ, anchorX, anchorZ, 0, radius));
        assertFalse(WaveMath.chunkIntersectsAnnulus(anchorChunkX + 6, anchorChunkZ, anchorX, anchorZ, 0, radius));
        assertFalse(WaveMath.chunkIntersectsAnnulus(anchorChunkX, anchorChunkZ + 6, anchorX, anchorZ, 0, radius));
    }
}
