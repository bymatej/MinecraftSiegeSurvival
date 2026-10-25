package com.bymatej.siege;

import com.bymatej.siege.ai.SiegeBrain;
import com.bymatej.siege.command.SiegeCommand;
import com.bymatej.siege.game.SiegeGame;
import com.bymatej.siege.game.SiegeHud;
import com.bymatej.siege.listener.SiegeEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SiegePlugin extends JavaPlugin {
    private SiegeGame game;
    private SiegeBrain brain;

    @Override public void onEnable() {
        saveDefaultConfig();
        game = new SiegeGame(this);
        SiegeCommand commands = new SiegeCommand(this, game);
        Objects.requireNonNull(getCommand("siege")).setExecutor(commands);
        Objects.requireNonNull(getCommand("siege")).setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(commands, this);
        getServer().getPluginManager().registerEvents(new SiegeEvents(this, game), this);
        SiegeBrain brain = new SiegeBrain(this, game);
        this.brain = brain;
        SiegeHud hud = new SiegeHud(this, game);
        // The HUD runs after the game tick so the on-screen countdown matches
        // the seconds announced in chat.
        getServer().getScheduler().runTaskTimer(this, () -> { game.tick(); hud.tick(); }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, brain::tick, 10L, 10L);
        // Climbing and building up need a steady push, so they run every tick.
        // It only touches the mobs that are actually on a ladder or on a pillar.
        getServer().getScheduler().runTaskTimer(this, brain::movementTick, 1L, 1L);
        getLogger().info("SiegeSurvival enabled; back up your survival world before using mining.");
        String protection = protectionPlugin();
        if (protection != null) {
            getLogger().warning("Siege: " + protection + " is installed. Siege mobs will not break any "
                    + "block while mining.deny-if-protection-plugin-present is true; set it to false if "
                    + "you accept that siege mobs breach protected blocks.");
        }
    }

    /**
     * The first known claim plugin that is installed, or null when there is
     * none. Siege mobs never break blocks in a protected region unless the
     * admin explicitly allows it.
     */
    public String protectionPlugin() {
        for (String name : java.util.List.of("WorldGuard", "GriefPrevention", "Lands", "Towny",
                "Residence", "GriefDefender", "ClaimChunk")) {
            if (getServer().getPluginManager().getPlugin(name) != null) return name;
        }
        return null;
    }

    @Override public void onDisable() {
        if (game != null) game.stop("Server shutting down");
        getServer().getScheduler().cancelTasks(this);
    }

    public SiegeGame game() { return game; }

    /** The AI controller, so a test harness can drive it. */
    public SiegeBrain brain() { return brain; }
}
