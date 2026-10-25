package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiningTimesTest {
    @Test void theRightToolIsMuchFaster() {
        // Stone: hardness 1.5, destroy speed 1.5.
        int withPickaxe = MiningTimes.ticks(1.5, 1.5, true);
        int withBareHands = MiningTimes.ticks(1.5, 1.5, false);
        assertEquals(30, withPickaxe);
        assertEquals(100, withBareHands);
        assertTrue(withBareHands > withPickaxe * 2);
    }

    @Test void aDesperateBreakGetsThroughWithBareHands() {
        // Bare hands on stone are slow, but they are not hopeless.
        int normal = MiningTimes.ticks(1.5, 1.5, false);
        int desperate = MiningTimes.desperateTicks(1.5, 1.5, false);
        assertTrue(desperate > 0);
        assertTrue(desperate < normal);
        // With the right tool the desperate break is as fast as the normal one.
        assertEquals(MiningTimes.ticks(1.5, 1.5, true),
                MiningTimes.desperateTicks(1.5, 1.5, true));
    }

    @Test void aDesperateBreakIsNotInstant() {
        // The mob still needs a moment per block, it does not vaporize walls.
        assertTrue(MiningTimes.desperateTicks(1.5, 1.5, false) >= 30);
        // Obsidian and the like are hopeless anyway.
        assertTrue(MiningTimes.desperateTicks(50, 1.5, false) > 1000);
    }

    @Test void theSearchPriceOfABreakIsCapped() {
        // The planner only picks where to dig: a slow break may not cost more
        // than a few steps, or the mob walks around the wall instead.
        assertEquals(4.0, MiningTimes.searchCost(10000), 0.0001);
        assertEquals(2.5, MiningTimes.searchCost(30), 0.0001);
        assertEquals(1.0, MiningTimes.searchCost(12), 0.0001);
    }
}
