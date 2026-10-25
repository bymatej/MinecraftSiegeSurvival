package com.bymatej.siege.game;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MobLedgerTest {
    @Test void aLivingMobIsNotGone() {
        assertFalse(MobLedger.gone(true, false, true));
    }

    @Test void aDeadMobIsGone() {
        assertTrue(MobLedger.gone(true, true, true));
    }

    @Test void aMobThatVanishedIsGone() {
        // The mob was removed from the world (an admin's /kill, another plugin)
        // and its chunk is still loaded: there is nothing left to kill.
        assertTrue(MobLedger.gone(false, false, true));
    }

    @Test void aMobInAnUnloadedChunkIsNotGone() {
        // It cannot be found, but it is stored in the chunk data and comes back.
        assertFalse(MobLedger.gone(false, false, false));
    }

    @Test void onlyAChunkUnloadStoresTheMob() {
        // A real removal (an admin's /kill, another plugin) is final.
        assertFalse(MobLedger.storedInChunkData(false));
        // A chunk unload only stores the mob: it comes back with the chunk.
        assertTrue(MobLedger.storedInChunkData(true));
    }

    /**
     * The living mobs must survive the filter - an inverted rule emptied every
     * wave one tick after it started.
     */
    @Test void theFilterKeepsTheLivingMobs() {
        UUID living = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        Set<UUID> tracked = new LinkedHashSet<>(Set.of(living, dead));
        Map<UUID, Boolean> gone = new HashMap<>();
        gone.put(living, MobLedger.gone(true, false, true));
        gone.put(dead, MobLedger.gone(true, true, true));
        assertEquals(1, MobLedger.removeGone(tracked, gone));
        assertEquals(Set.of(living), tracked);
    }

    /** An unknown state must never clear a wave. */
    @Test void anUnknownMobKeepsCounting() {
        UUID unknown = UUID.randomUUID();
        Set<UUID> tracked = new LinkedHashSet<>(Set.of(unknown));
        assertEquals(1, MobLedger.removeGone(tracked, new HashMap<>()));
        assertTrue(tracked.contains(unknown));
    }
}
