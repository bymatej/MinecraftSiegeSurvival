package com.bymatej.siege.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.UUID;

public final class PlayerRecord {
    private final UUID id;
    private final Location before;
    private final GameMode gameMode;
    private boolean alive = true;
    private long disconnectedAt = -1;

    public PlayerRecord(Player player) {
        id = player.getUniqueId();
        before = player.getLocation().clone();
        gameMode = player.getGameMode();
    }
    public UUID id() { return id; }
    public Location before() { return before.clone(); }
    public GameMode gameMode() { return gameMode; }
    public boolean alive() { return alive; }
    public void eliminate() { alive = false; }
    public long disconnectedAt() { return disconnectedAt; }
    public void disconnect(long second) { disconnectedAt = second; }
    public void reconnect() { disconnectedAt = -1; }
}
