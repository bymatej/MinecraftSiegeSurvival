package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainToolsTest {
    @Test void noGapMeansNoAction() {
        assertEquals(0, TerrainTools.gapWidth(new boolean[]{true}, 3));
        assertEquals(TerrainTools.Action.NONE, TerrainTools.decide(0, 0));
    }

    @Test void oneMissingFloorIsAJumpableGap() {
        assertEquals(1, TerrainTools.gapWidth(new boolean[]{false, true}, 3));
        assertEquals(TerrainTools.Action.GAP, TerrainTools.decide(1, 1));
    }

    @Test void aWiderGapHasToBeBridged() {
        assertEquals(2, TerrainTools.gapWidth(new boolean[]{false, false, true}, 3));
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(2, 1));
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(3, 1));
    }

    @Test void aDeepHoleIsBridgedEvenWhenItIsNarrow() {
        // A mob cannot jump out of a deep trench: it builds a floor across it.
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(1, 2));
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(1, 3));
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(1, 5));
    }

    @Test void aHoleOfTwoBlocksIsBridged() {
        // The mob would fall into a two block deep hole and never get out.
        assertEquals(TerrainTools.Action.BRIDGE, TerrainTools.decide(1, 2));
    }

    @Test void theGapIsOnlyMeasuredUpToTheLimit() {
        // A long trench counts as the limit, not as an endless hole.
        assertEquals(3, TerrainTools.gapWidth(new boolean[]{false, false, false, false}, 3));
        assertEquals(2, TerrainTools.gapWidth(new boolean[]{false, false, false, false}, 2));
    }

    @Test void aFloorAfterTheGapStopsTheCounting() {
        assertEquals(1, TerrainTools.gapWidth(new boolean[]{false, true, false}, 3));
    }
}
