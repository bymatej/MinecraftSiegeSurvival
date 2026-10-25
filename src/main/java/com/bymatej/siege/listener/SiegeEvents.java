package com.bymatej.siege.listener;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.ai.MiningPolicy;
import com.bymatej.siege.game.MobLedger;
import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;

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

    /**
     * Siege mobs are health-scaled (10% in the first wave, about 3 HP in the
     * second), so a fall, a fire tick, suffocation or a few starvation ticks
     * kills them instantly. That emptied waves without the player killing
     * anything, so only combat damage is allowed through.
     */
    @EventHandler(ignoreCancelled = true)
    public void siegeMobDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;
        if (!game.isSiegeMob(entity)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        switch (cause) {
            // Combat damage stays: melee, sweeps, arrows, potions and thorns.
            // A /kill is the explicit wish of the admin, so it stays as well -
            // otherwise the enemy could not be removed by hand at all.
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, CUSTOM, MAGIC, THORNS, POISON, WITHER, KILL -> {
                if (event.getFinalDamage() >= living.getHealth()) {
                    String killer = event instanceof EntityDamageByEntityEvent by
                            ? " by " + by.getDamager().getType()
                            : "";
                    plugin.getLogger().info("Siege: " + entity.getType() + " died from "
                            + cause + killer + ".");
                }
            }
            // A creeper dies in its own explosion, but it must not take the
            // other siege mobs with it.
            case ENTITY_EXPLOSION -> {
                if (!(event instanceof EntityDamageByEntityEvent by) || by.getDamager() != entity) {
                    event.setCancelled(true);
                }
            }
            // Everything else is environmental (falls, fire, lava, starvation,
            // suffocation, the void, ...) and would kill a 10%-health mob.
            default -> event.setCancelled(true);
        }
    }

    /**
     * A mob that left the world without dying - an admin's /kill, another
     * plugin - does not count towards the wave any more. A mob that was only
     * stored because its chunk unloaded is still alive and is kept.
     */
    @EventHandler
    public void mobRemoved(EntityRemoveEvent event) {
        // A chunk unload only stores the mob in the chunk data: it comes back
        // with the chunk and still counts. Every other cause (a death, an
        // admin's /kill, another plugin) means it is gone for good.
        if (MobLedger.storedInChunkData(event.getCause() == EntityRemoveEvent.Cause.UNLOAD)) return;
        if (!game.isSiegeMob(event.getEntity())) return;
        game.onMobRemoved(event.getEntity(), event.getCause());
    }

    /**
     * A siege creeper must not blow up the arena: the explosion still hurts
     * players, but it does not touch the terrain. The same applies to the
     * vanilla block breaking of a charging ravager.
     */
    @EventHandler(ignoreCancelled = true)
    public void siegeExplosion(EntityExplodeEvent event) {
        if (!game.isSiegeMob(event.getEntity())) return;
        event.blockList().clear();
    }

    /**
     * A charging ravager smashes through simple blocks - that is what it is
     * for. Every other siege mob keeps its hands off the terrain, and even the
     * ravager is limited to the destruction radius and a whitelist of plain
     * building materials.
     */
    @EventHandler(ignoreCancelled = true)
    public void siegeBlockChange(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (!game.isSiegeMob(entity)) return;
        if (!(entity instanceof Ravager)
                || !plugin.getConfig().getBoolean("mining.enabled", false)
                || !plugin.getConfig().getBoolean("mining.ravager-smashes-blocks", true)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getBlock();
        if (!game.insideDestruction(block.getLocation()) || !MiningPolicy.maySmash(block)
                || claimPluginBlocksMining()) {
            event.setCancelled(true);
            return;
        }
        // Journal the block so the match can restore it afterwards.
        game.journalMobBreak(block);
    }

    /**
     * A claim plugin vetoes every block change of a siege mob, unless the
     * server owner explicitly accepted that the siege breaches protected
     * blocks.
     */
    private boolean claimPluginBlocksMining() {
        return plugin.getConfig().getBoolean("mining.deny-if-protection-plugin-present", true)
                && plugin.protectionPlugin() != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) {
        if (!game.running() || !game.enrolled(event.getPlayer().getUniqueId())) return;
        GameMode required = game.alive(event.getPlayer().getUniqueId()) ?
                GameMode.SURVIVAL : GameMode.SPECTATOR;
        if (event.getNewGameMode() != required) event.setCancelled(true);
    }

    /**
     * One life per match: the eliminated player skips the respawn screen and
     * goes straight into spectator mode instead of coming back as a player.
     */
    @EventHandler public void death(PlayerDeathEvent event) {
        if (!game.running() || !game.alive(event.getEntity().getUniqueId())) return;
        Player player = event.getEntity();
        game.eliminate(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.isDead()) return;
            // Respawns without the death screen; PlayerRespawnEvent moves the
            // player to the anchor.
            player.spigot().respawn();
            if (player.isOnline()) player.setGameMode(GameMode.SPECTATOR);
        });
    }

    /**
     * Paper 26.2 never fires PlayerPostRespawnEvent, so the work that has to
     * happen once the player is back in the world is scheduled from here -
     * PlayerRespawnEvent is fired for every respawn.
     */
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (game.running() && game.eliminated(player.getUniqueId())) {
            event.setRespawnLocation(game.anchor());
        }
        Bukkit.getScheduler().runTask(plugin, () -> afterRespawn(player));
    }

    private void afterRespawn(Player player) {
        if (!player.isOnline()) return;
        if (!game.running()) {
            // Restores the game mode and location journaled before an
            // interrupted match.
            game.recoverIfNeeded(player);
            return;
        }
        if (!game.eliminated(player.getUniqueId())) return;
        player.setGameMode(GameMode.SPECTATOR);
        Player follow = game.livingOnline().stream().findFirst().orElse(null);
        if (follow != null) player.setSpectatorTarget(follow);
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

    @EventHandler(ignoreCancelled = true)
    public void siegeMobPortal(EntityPortalEvent event) {
        if (game.isSiegeMob(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void siegeMobTransform(EntityTransformEvent event) {
        // Entity replacement would lose the UUID-based wave tracking and cause false clears.
        if (game.isSiegeMob(event.getEntity())) event.setCancelled(true);
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
