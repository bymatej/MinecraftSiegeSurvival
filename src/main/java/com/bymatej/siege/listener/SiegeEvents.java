package com.bymatej.siege.listener;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;

public final class SiegeEvents implements Listener {
    private final SiegePlugin plugin;
    private final SiegeGame game;
    public SiegeEvents(SiegePlugin plugin, SiegeGame game) {
        this.plugin = plugin; this.game = game;
    }

    /** PlayerTeleportEvent extends PlayerMoveEvent; destination checks include all teleport causes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void movement(PlayerMoveEvent event) {
        if (!game.combat() || !game.enrolled(event.getPlayer().getUniqueId())) return;
        Location to = event.getTo();
        if (to == null || !game.outside(to)) return;
        if (event.getFrom().getWorld() != to.getWorld()
                || event.getFrom().getX() != to.getX()
                || event.getFrom().getZ() != to.getZ()) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(
                    "You cannot leave the siege boundary!"));
        }
    }

    /** VehicleMoveEvent cannot be cancelled; return a participant's vehicle to its previous legal position. */
    @EventHandler public void vehicle(VehicleMoveEvent event) {
        if (!game.combat() || !game.outside(event.getTo())) return;
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player p && game.enrolled(p.getUniqueId())) {
                Location destination = event.getFrom();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (game.combat() && event.getVehicle().isValid()) event.getVehicle().teleport(destination);
                });
                break;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) {
        if (!game.running() || !game.enrolled(event.getPlayer().getUniqueId())) return;
        GameMode required = game.alive(event.getPlayer().getUniqueId()) ?
                GameMode.SURVIVAL : GameMode.SPECTATOR;
        if (event.getNewGameMode() != required) event.setCancelled(true);
    }

    @EventHandler public void death(PlayerDeathEvent event) {
        if (game.running() && game.alive(event.getEntity().getUniqueId())) game.eliminate(event.getEntity());
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        if (game.running() && game.eliminated(event.getPlayer().getUniqueId())) {
            event.setRespawnLocation(game.anchor());
        }
    }

    @EventHandler public void postRespawn(PlayerPostRespawnEvent event) {
        if (!game.running()) {
            game.recoverIfNeeded(event.getPlayer());
            return;
        }
        if (game.running() && game.eliminated(event.getPlayer().getUniqueId())) {
            event.getPlayer().setGameMode(GameMode.SPECTATOR);
            Player follow = game.livingOnline().stream().findFirst().orElse(null);
            if (follow != null) event.getPlayer().setSpectatorTarget(follow);
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        if (game.running()) game.disconnected(event.getPlayer());
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (game.running()) game.joined(event.getPlayer());
            else game.recoverIfNeeded(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (!game.insideCombat(event.getLocation()) ||
                plugin.getConfig().getBoolean("arena.natural-spawns", false)) return;
        switch (event.getSpawnReason().name()) {
            case "NATURAL", "CHUNK_GEN", "PATROL", "REINFORCEMENTS", "SPAWNER", "VILLAGE_INVASION" ->
                    event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void targeting(EntityTargetLivingEntityEvent event) {
        if (!game.isSiegeMob(event.getEntity())) return;
        if (event.getTarget() instanceof Player player) {
            if (!game.alive(player.getUniqueId())) event.setCancelled(true);
        } else if (event.getTarget() != null) {
            event.setCancelled(true);
        }
    }
}
