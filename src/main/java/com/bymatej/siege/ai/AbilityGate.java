package com.bymatej.siege.ai;

/**
 * The gates that unlock a mob ability. An ability can be tied to the progress
 * of the match (a percentage of the waves) and to the time a wave has been
 * running. Pure, so the rules are unit tested.
 */
public final class AbilityGate {
    private AbilityGate() { }

    /**
     * True when the wave is far enough for a percentage gated ability.
     *
     * @param wave the current wave, starting at 1
     * @param totalWaves the number of waves in the match
     * @param percent the percentage of the match the ability starts at;
     *                0 = from the first wave on
     */
    public static boolean percent(int wave, int totalWaves, int percent) {
        if (percent <= 0) return true;
        if (totalWaves <= 0) return false;
        return wave * 100 >= totalWaves * percent;
    }

    /**
     * True when the percentage of the waves unlocks the ability, or when the
     * wave has been running long enough.
     *
     * <p>The time gate lets a mob that is stuck for a long time use an ability
     * that is normally a late game one - building itself up to a player who
     * hid on a roof, for example. It is measured in seconds into the wave.
     *
     * @param waveSeconds the seconds the current wave has been running
     * @param afterSeconds the seconds after which the ability is unlocked;
     *                     0 = the time gate is off
     */
    public static boolean percentOrTime(int wave, int totalWaves, int percent,
                                        int waveSeconds, int afterSeconds) {
        if (percent(wave, totalWaves, percent)) return true;
        return afterSeconds > 0 && waveSeconds >= afterSeconds;
    }
}
