package com.bymatej.siege.game;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rules that decide whether a tracked siege mob still counts towards the
 * wave. No Bukkit types, so the rules are unit tested.
 *
 * <p>The wave is only cleared when every enemy is really gone. A mob that was
 * killed by an admin with {@code /kill} or that was removed by another plugin
 * is gone for good - it must not keep the wave alive. A mob that only left the
 * loaded area is not: it is stored in the chunk data and comes back with the
 * chunk.
 *
 * <p>The rules are phrased as "the mob is gone" on purpose: the caller drops
 * the mobs for which this is true, so an inverted rule would empty every wave
 * instead of keeping the living enemies.
 */
public final class MobLedger {
    private MobLedger() { }

    /**
     * True when the mob is gone and must not count towards the wave any more.
     *
     * @param entityFound the mob was found in a loaded chunk
     * @param entityDead the found mob is dead
     * @param lastChunkLoaded the chunk of the last known position is loaded
     */
    public static boolean gone(boolean entityFound, boolean entityDead, boolean lastChunkLoaded) {
        if (entityFound) return entityDead;
        // Not found: the mob is only gone when the chunk it was last seen in is
        // loaded as well. Otherwise it waits in an unloaded chunk and is alive.
        return lastChunkLoaded;
    }

    /**
     * Drops every mob that is gone and returns how many enemies are left.
     *
     * <p>A mob that is missing from the map is kept: an unknown state must
     * never clear a wave.
     *
     * @param tracked the mobs of the wave
     * @param gone per mob: true when the mob is gone for good
     */
    public static int removeGone(Set<UUID> tracked, Map<UUID, Boolean> gone) {
        tracked.removeIf(id -> Boolean.TRUE.equals(gone.get(id)));
        return tracked.size();
    }

    /**
     * True when a removal only stored the mob in the chunk data: a chunk
     * unload saves it and it comes back with the chunk.
     *
     * @param chunkUnload the mob was removed because its chunk unloaded
     */
    public static boolean storedInChunkData(boolean chunkUnload) {
        return chunkUnload;
    }
}
