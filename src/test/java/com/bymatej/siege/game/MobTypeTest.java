package com.bymatej.siege.game;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobTypeTest {
    private static final EnumSet<MobType> ALL = EnumSet.allOf(MobType.class);

    @Test void typesUnlockAsTheWavesProgress() {
        List<MobType> wave1 = MobType.activeFor(1, 10, ALL);
        assertTrue(wave1.contains(MobType.ZOMBIE));
        assertTrue(wave1.contains(MobType.SKELETON));
        assertFalse(wave1.contains(MobType.PILLAGER));
        assertFalse(wave1.contains(MobType.VINDICATOR));
        assertFalse(wave1.contains(MobType.CREEPER));

        List<MobType> wave3 = MobType.activeFor(3, 10, ALL);
        assertTrue(wave3.contains(MobType.VINDICATOR));
        assertFalse(wave3.contains(MobType.WITHER_SKELETON));

        List<MobType> wave7 = MobType.activeFor(7, 10, ALL);
        assertTrue(wave7.contains(MobType.CREEPER));
        assertFalse(wave7.contains(MobType.RAVAGER));
    }

    @Test void theRavagerOnlyShowsUpInTheFinalWave() {
        assertTrue(MobType.activeFor(10, 10, ALL).contains(MobType.RAVAGER));
        assertFalse(MobType.activeFor(9, 10, ALL).contains(MobType.RAVAGER));
        // A one wave match is its own final wave.
        assertTrue(MobType.activeFor(1, 1, ALL).contains(MobType.RAVAGER));
    }

    @Test void disabledTypesNeverTakePart() {
        EnumSet<MobType> enabled = EnumSet.of(MobType.ZOMBIE, MobType.BLAZE);
        List<MobType> wave6 = MobType.activeFor(6, 10, enabled);
        assertEquals(List.of(MobType.ZOMBIE, MobType.BLAZE), wave6);
    }

    @Test void theTypesOfAWaveTakeTurns() {
        List<MobType> active = MobType.activeFor(7, 10, ALL);
        Map<MobType, Integer> spawned = new EnumMap<>(MobType.class);
        MobType first = MobType.pick(active, 0, spawned);
        spawned.merge(first, 1, Integer::sum);
        MobType second = MobType.pick(active, 1, spawned);
        assertNotEquals(first, second);
        assertTrue(active.contains(first));
        assertTrue(active.contains(second));
    }

    @Test void aCappedTypeIsSkipped() {
        List<MobType> active = List.of(MobType.RAVAGER, MobType.ZOMBIE);
        Map<MobType, Integer> spawned = new EnumMap<>(MobType.class);
        // The ravager is due first, but it already reached its cap.
        spawned.put(MobType.RAVAGER, 1);
        assertEquals(MobType.ZOMBIE, MobType.pick(active, 0, spawned));
        // Without the cap the ravager would be picked.
        Map<MobType, Integer> fresh = new EnumMap<>(MobType.class);
        assertEquals(MobType.RAVAGER, MobType.pick(active, 0, fresh));
    }

    @Test void anEmptyRosterSpawnsNothing() {
        assertTrue(MobType.activeFor(1, 10, EnumSet.noneOf(MobType.class)).isEmpty());
        assertNull(MobType.pick(List.of(), 0, new EnumMap<>(MobType.class)));
    }

    @Test void aTenWaveMatchEscalates() {
        assertEquals(2, MobType.activeFor(1, 10, ALL).size());
        assertEquals(3, MobType.activeFor(2, 10, ALL).size());
        assertEquals(4, MobType.activeFor(3, 10, ALL).size());
        assertEquals(9, MobType.activeFor(10, 10, ALL).size());
    }

    @Test void aWaveIsAnEvenMixOfTheUnlockedTypes() {
        List<MobType> active = MobType.activeFor(3, 10, ALL);
        Map<MobType, Integer> spawned = new EnumMap<>(MobType.class);
        for (int index = 0; index < active.size(); index++) {
            MobType type = MobType.pick(active, index, spawned);
            spawned.merge(type, 1, Integer::sum);
        }
        assertEquals(active.size(), spawned.size());
        assertTrue(spawned.values().stream().allMatch(count -> count == 1));
    }
}
