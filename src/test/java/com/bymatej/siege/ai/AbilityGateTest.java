package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbilityGateTest {
    @Test void zeroPercentIsAlwaysUnlocked() {
        assertTrue(AbilityGate.percent(1, 10, 0));
        assertTrue(AbilityGate.percent(1, 1, 0));
    }

    @Test void theLastQuarterIsTheLateGame() {
        // 10 waves: the last quarter starts with wave 8.
        assertFalse(AbilityGate.percent(7, 10, 75));
        assertTrue(AbilityGate.percent(8, 10, 75));
        assertTrue(AbilityGate.percent(10, 10, 75));
    }

    @Test void aMatchWithoutWavesUnlocksNothing() {
        assertFalse(AbilityGate.percent(1, 0, 75));
    }

    @Test void theTimeGateUnlocksTheAbilityDuringTheWave() {
        // The wave percent is not reached yet ...
        assertFalse(AbilityGate.percentOrTime(1, 10, 75, 100, 300));
        // ... but five minutes into the wave the mobs may build themselves up.
        assertTrue(AbilityGate.percentOrTime(1, 10, 75, 300, 300));
        assertTrue(AbilityGate.percentOrTime(1, 10, 75, 301, 300));
    }

    @Test void aZeroTimeGateKeepsTheAbilityLocked() {
        assertFalse(AbilityGate.percentOrTime(1, 10, 75, 3600, 0));
    }
}
