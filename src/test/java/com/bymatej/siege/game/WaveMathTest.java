package com.bymatej.siege.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveMathTest {
    @Test void healthScalesToLimits() {
        assertEquals(0.10, WaveMath.healthMultiplier(1, 25), 0.000001);
        assertEquals(1.50, WaveMath.healthMultiplier(25, 25), 0.000001);
        assertEquals(0.10, WaveMath.healthMultiplier(1, 1), 0.000001);
        assertThrows(IllegalArgumentException.class, () -> WaveMath.healthMultiplier(0, 25));
    }

    @Test void mobCountsAreCapped() {
        assertEquals(5, WaveMath.enemies(1, 1, 120, 5, 1.3, 0.45));
        assertEquals(37, WaveMath.enemies(25, 1, 120, 5, 1.3, 0.45));
        assertEquals(120, WaveMath.enemies(100, 4, 120, 5, 1.3, 0.45));
    }

    @Test void circularBoundaryUsesHorizontalDistance() {
        assertFalse(WaveMath.outside(100, 0, 0, 0, 100));
        assertTrue(WaveMath.outside(101, 0, 0, 0, 100));
        assertTrue(WaveMath.outside(80, 80, 0, 0, 100));
    }
}
