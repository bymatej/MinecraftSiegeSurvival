package com.bymatej.siege.game;

import com.bymatej.siege.SiegePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * On-screen siege information.
 *
 * <p>Two channels are used:
 * <ul>
 *   <li>the action bar above the hotbar carries the live counters and the
 *       countdown before a wave, so it is visible without flooding the chat;</li>
 *   <li>the chat can carry the same summary on an interval, but that is off
 *       by default - only the countdown and the meaningful events are
 *       announced in chat.</li>
 * </ul>
 *
 * <p>The HUD never mutates match state; it only reads it.
 */
public final class SiegeHud {
    private final SiegePlugin plugin;
    private final SiegeGame game;
    private int statusTimer;
    private boolean actionBarActive;

    public SiegeHud(SiegePlugin plugin, SiegeGame game) {
        this.plugin = plugin;
        this.game = game;
    }

    /**
     * Called once per second, after the game tick, so the countdown matches
     * the seconds the chat announced.
     */
    public void tick() {
        if (!game.running()) {
            if (actionBarActive) {
                clearActionBar();
                actionBarActive = false;
            }
            statusTimer = 0;
            return;
        }
        updateActionBar();
        updateChatStatus();
    }

    private void updateActionBar() {
        SiegeState state = game.state();
        Component line = null;
        if (state == SiegeState.PREPARING || state == SiegeState.INTERMISSION) {
            int window = Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 5));
            int remaining = game.secondsRemaining();
            if (remaining > 0 && remaining <= window) {
                line = Component.text("Wave " + game.nextWave() + " starts in ", NamedTextColor.GOLD)
                        .append(Component.text(remaining + "s", NamedTextColor.RED));
            }
        } else if (state == SiegeState.WAVE) {
            line = counters();
        }
        if (line == null) return;
        for (Player player : game.livingOnline()) {
            player.sendActionBar(line);
        }
        actionBarActive = true;
    }

    private void clearActionBar() {
        for (Player player : game.livingOnline()) {
            player.sendActionBar(Component.empty());
        }
    }

    private void updateChatStatus() {
        int interval = plugin.getConfig().getInt("game.status-interval-seconds", 0);
        if (interval <= 0) return;
        if (statusTimer > 0) {
            statusTimer--;
            return;
        }
        statusTimer = interval;
        if (game.state() != SiegeState.WAVE) return;
        game.announce(plainCounters());
    }

    private Component counters() {
        return Component.text("Wave " + game.wave() + "/" + game.totalWaves(), NamedTextColor.GOLD)
                .append(Component.text(" | Spawned " + game.spawnedThisWave(), NamedTextColor.GRAY))
                .append(Component.text(" | Left " + (game.aliveMobs() + game.pendingMobs()), NamedTextColor.YELLOW));
    }

    private String plainCounters() {
        return "Wave " + game.wave() + "/" + game.totalWaves()
                + " | Spawned " + game.spawnedThisWave()
                + " | Left " + (game.aliveMobs() + game.pendingMobs());
    }
}
