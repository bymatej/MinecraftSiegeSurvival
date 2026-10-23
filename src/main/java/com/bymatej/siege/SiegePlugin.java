package com.bymatej.siege;

import com.bymatej.siege.ai.SiegeBrain;
import com.bymatej.siege.command.SiegeCommand;
import com.bymatej.siege.game.SiegeGame;
import com.bymatej.siege.listener.SiegeEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SiegePlugin extends JavaPlugin {
    private SiegeGame game;

    @Override public void onEnable() {
        saveDefaultConfig();
        game = new SiegeGame(this);
        SiegeCommand commands = new SiegeCommand(this, game);
        Objects.requireNonNull(getCommand("siege")).setExecutor(commands);
        Objects.requireNonNull(getCommand("siege")).setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(commands, this);
        getServer().getPluginManager().registerEvents(new SiegeEvents(this, game), this);
        SiegeBrain brain = new SiegeBrain(this, game);
        getServer().getScheduler().runTaskTimer(this, game::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, brain::tick, 10L, 10L);
        getLogger().info("SiegeSurvival enabled; back up your survival world before using mining.");
    }

    @Override public void onDisable() {
        if (game != null) game.stop("Server shutting down");
        getServer().getScheduler().cancelTasks(this);
    }

    public SiegeGame game() { return game; }
}
