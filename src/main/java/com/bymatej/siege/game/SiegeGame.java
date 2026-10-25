package com.bymatej.siege.game;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.arena.BlockJournal;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityRemoveEvent;

import java.util.*;

/**
 * One server-wide session. All calls must run on the main Paper server thread.
 * No global world rules or borders are mutated.
 */
public final class SiegeGame {
    /** Spectral-arrow style outline, applied to enemies of a stalled wave. */
    private static final PotionEffect GLOW = new PotionEffect(PotionEffectType.GLOWING,
            PotionEffect.INFINITE_DURATION, 0);

    private final SiegePlugin plugin;
    private final NamespacedKey mobTag;
    private final RecoveryStore recovery;
    private final BlockJournal blockJournal;
    private final Random random = new Random();
    private final LinkedHashMap<UUID, PlayerRecord> players = new LinkedHashMap<>();
    private final Map<UUID, PlayerRecord> visitors = new LinkedHashMap<>();
    private final Set<UUID> mobs = new HashSet<>();
    private final EnumSet<MobType> allowed = EnumSet.noneOf(MobType.class);
    /** The types that may spawn in the running wave. */
    private List<MobType> waveTypes = List.of();
    private final Map<MobType, Integer> spawnedPerType = new EnumMap<>(MobType.class);
    private int spawnCursor;
    private SiegeState state = SiegeState.IDLE;
    private Location draftAnchor;
    private Location anchor;
    private int radius;
    private int totalWaves;
    private int wave;
    private int seconds;
    private int pending;
    private int spawned;
    private int initialPlayers;
    private int noLivingSeconds;
    private int failedSpawnSeconds;
    private boolean chunksHeld;
    private boolean glowAnnounced;
    /** True when the admin forced the mobs to glow with /siege glow. */
    private boolean forceGlow;
    /** The last known position of every living siege mob. */
    private final Map<UUID, Location> mobPositions = new HashMap<>();
    private int waveSeconds;

    public SiegeGame(SiegePlugin plugin) {
        this.plugin = plugin;
        this.recovery = new RecoveryStore(plugin);
        this.blockJournal = new BlockJournal(plugin);
        this.blockJournal.restore(); // Recover uncommitted mob damage after server restart.
        this.mobTag = new NamespacedKey(plugin, "siege_match_mob");
        this.radius = plugin.getConfig().getInt("arena.movement-radius", 60);
        for (MobType type : MobType.values()) {
            if (plugin.getConfig().getBoolean("mobs." + type.key(), type.defaultEnabled())) allowed.add(type);
        }
        // Entity cleanup on startup prevents stray old plugin mobs from blocking the next match.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(mobTag, PersistentDataType.BYTE)) entity.remove();
            }
        }
    }

    public SiegeState state() { return state; }
    public boolean running() { return state != SiegeState.IDLE; }
    public boolean combat() { return state == SiegeState.WAVE || state == SiegeState.INTERMISSION; }
    public int wave() { return wave; }
    public int totalWaves() { return totalWaves; }
    public int secondsRemaining() { return seconds; }
    public int spawnedThisWave() { return spawned; }
    public int aliveMobs() { return mobs.size(); }
    public int pendingMobs() { return pending; }
    public int nextWave() { return wave + 1; }
    public int radius() { return radius; }
    public Location anchor() { return anchor == null ? null : anchor.clone(); }
    public boolean journalMobBreak(Block block) {
        return !plugin.getConfig().getBoolean("arena.rollback-mob-damage", true) || blockJournal.record(block);
    }

    /**
     * Records a block that a siege mob placed, so that it is removed again
     * when the match ends.
     */
    public boolean journalMobPlace(Block block, Material placed) {
        return !plugin.getConfig().getBoolean("arena.rollback-mob-damage", true)
                || blockJournal.recordPlaced(block, placed);
    }
    public boolean isSiegeMob(Entity e) { return mobs.contains(e.getUniqueId()); }
    public Set<UUID> mobIds() { return Set.copyOf(mobs); }
    public boolean enrolled(UUID uuid) { return players.containsKey(uuid) || visitors.containsKey(uuid); }
    public boolean alive(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        return record != null && record.alive();
    }
    public boolean eliminated(UUID uuid) { return enrolled(uuid) && !alive(uuid); }
    public boolean allowed(MobType type) { return allowed.contains(type); }

    public String toggle(MobType type) {
        if (running()) return "Cannot edit mobs during a match.";
        if (!allowed.remove(type)) allowed.add(type);
        return type.display() + (allowed.contains(type) ? " enabled" : " disabled");
    }

    public String setRadius(int value) {
        if (running()) return "Cannot change the radius while a match is running.";
        int max = plugin.getConfig().getInt("arena.combat-radius", 80);
        if (value < 25 || value > max) return "Radius must be between 25 and " + max + " blocks.";
        radius = value;
        return "Siege boundary set to " + radius + " blocks.";
    }

    public String saveAnchor(Player initiator) {
        if (running()) return "Anchor cannot be changed during a match.";
        if (!hasSafeFooting(initiator.getLocation()))
            return "Stand on a safe solid block to set an anchor (Overworld, Nether or End).";
        draftAnchor = initiator.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN)
                .getLocation().add(0.5, 1, 0.5);
        return "Anchor saved at " + coordinates(draftAnchor) + ".";
    }

    public String start(Player initiator, int requestedWaves, int prepMinutes) {
        if (running()) return "A siege is already in progress.";
        if (allowed.isEmpty()) return "Enable at least one mob in /siege setup.";
        int max = plugin.getConfig().getInt("game.max-waves", 100);
        if (requestedWaves < 1 || requestedWaves > max) return "Wave count must be 1-" + max + ".";
        if (MobType.activeFor(1, requestedWaves, allowed).isEmpty())
            return "Enable at least one mob that already appears in the first wave.";
        if (prepMinutes < 0 || prepMinutes > 240) return "Preparation must be between 0 and 240 minutes.";
        if (!hasSafeFooting(initiator.getLocation()))
            return "Stand on a safe solid floor to begin a siege (Overworld, Nether or End).";
        if (initiator.getWorld().getDifficulty() == Difficulty.PEACEFUL)
            return "Peaceful difficulty cannot support a siege. Change the world's difficulty first.";
        if (Bukkit.getOnlinePlayers().isEmpty()) return "No players are online.";
        if (draftAnchor != null && prepMinutes == 0
                && draftAnchor.getWorld() == initiator.getWorld() && !hasSafeFooting(draftAnchor))
            return "Saved anchor is unsafe. Stand on a solid block and run /siege anchor again.";
        Location chosen = (prepMinutes > 0 || draftAnchor == null
                || draftAnchor.getWorld() != initiator.getWorld())
                ? initiator.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getLocation().add(0.5, 1, 0.5)
                : draftAnchor.clone();
        anchor = chosen;
        totalWaves = requestedWaves;
        wave = 0;
        pending = 0;
        seconds = prepMinutes * 60;
        noLivingSeconds = 0;
        failedSpawnSeconds = 0;
        chunksHeld = false;
        waveSeconds = 0;
        forceGlow = false;
        players.clear();
        visitors.clear();
        mobs.clear();
        mobPositions.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerRecord record = new PlayerRecord(player);
            players.put(player.getUniqueId(), record);
            recovery.remember(record);
        }
        initialPlayers = players.size();
        // The movement boundary must remain inactive during initial player gathering.
        state = SiegeState.PREPARING;
        Location rendezvous = initiator.getLocation().clone();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.leaveVehicle();
            player.teleport(rendezvous); // All participants meet at the initiating player.
            player.setGameMode(GameMode.SURVIVAL);
        }
        if (prepMinutes > 0) {
            broadcast("Preparation started (" + prepMinutes + " minutes). Travel is UNRESTRICTED until wave 1.");
        } else {
            beginWave();
        }
        return "Siege started: " + totalWaves + " waves; boundary " + radius + " blocks (active during combat only).";
    }

    private void beginWave() {
        if (!chunksHeld) {
            holdSpawnChunks();
            chunksHeld = true;
        }
        wave++;
        waveSeconds = 0;
        glowAnnounced = false;
        // The forced glow belongs to the wave it was switched on in: the next
        // wave starts dark and glows on its own after five minutes.
        forceGlow = false;
        applyGlow(false);
        failedSpawnSeconds = 0;
        // The roster escalates: every type that is enabled and unlocked by now
        // takes part in the wave.
        waveTypes = MobType.activeFor(wave, totalWaves, allowed);
        spawnedPerType.clear();
        spawnCursor = 0;
        state = SiegeState.WAVE;
        seconds = 0;
        spawned = 0;
        if (wave == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (enrolled(player.getUniqueId()) && outside(player.getLocation())) player.teleport(anchor);
            }
        }
        int count = WaveMath.enemies(wave, initialPlayers,
                plugin.getConfig().getInt("waves.max-enemies-per-wave", 120),
                plugin.getConfig().getDouble("waves.base-enemies", 5),
                plugin.getConfig().getDouble("waves.growth-per-wave", 1.3));
        pending = count;
        broadcast("Wave " + wave + "/" + totalWaves + " begins! " + count + " enemies incoming.");
    }

    public void tick() {
        if (!running()) return;
        long now = System.currentTimeMillis() / 1000;
        for (PlayerRecord record : players.values()) {
            if (record.alive() && record.disconnectedAt() >= 0
                    && now - record.disconnectedAt() >= plugin.getConfig().getInt("game.reconnect-grace-seconds", 60)) {
                record.eliminate();
                broadcast("A disconnected player has been eliminated.");
            }
        }
        if (players.values().stream().noneMatch(PlayerRecord::alive)) {
            if (++noLivingSeconds >= 3) stop("DEFEAT: all participants eliminated");
            return;
        }
        noLivingSeconds = 0;
        if (state == SiegeState.PREPARING) {
            if (seconds > 0 && seconds % 60 == 0) broadcast("Siege begins in " + seconds / 60 + " minute(s).");
            if (--seconds <= 0) { beginWave(); return; }
            if (seconds == 30 || seconds == 10 || seconds == 5)
                broadcast("Wave " + nextWave() + " in " + seconds + " seconds. Return to base!");
            return;
        }
        if (state == SiegeState.INTERMISSION) {
            if (--seconds <= 0) { beginWave(); return; }
            if (seconds == 5) broadcast("Wave " + nextWave() + " in " + seconds + " seconds.");
            return;
        }
        waveSeconds++;
        int timeout = Math.max(120, plugin.getConfig().getInt("waves.max-wave-seconds", 1200));
        int remaining = timeout - waveSeconds;
        if (remaining == 60) {
            broadcast("Wave " + wave + " ends in 1 minute - kill the remaining enemies!");
        } else if (remaining > 0 && remaining <= 10) {
            broadcast("Wave " + wave + " aborts in " + remaining + " second" + (remaining == 1 ? "" : "s") + ".");
        }
        if (waveSeconds > timeout) {
            stop("ABORTED: wave " + wave + " exceeded " + timeout + " seconds; some enemies may be unreachable.");
            return;
        }
        dropGoneMobs();
        rescueEndangeredMobs();
        extinguishMobs();
        rememberMobPositions();
        applyLongWaveGlow();
        // A mob that left the loaded area does not tick: it neither moves
        // towards the players nor glows. Its chunk is held, so it comes back.
        recoverMobChunks();
        int batch = plugin.getConfig().getInt("waves.spawn-batch-size", 5);
        int limit = plugin.getConfig().getInt("waves.max-active-mobs", 40);
        int attempts = Math.min(Math.min(batch, pending), Math.max(0, limit - mobs.size()));
        int spawnedNow = 0;
        for (int i = 0; i < attempts; i++) {
            if (spawnEnemy()) { pending--; spawnedNow++; }
            else break;
        }
        if (pending > 0 && mobs.size() < limit) {
            failedSpawnSeconds = spawnedNow > 0 ? 0 : failedSpawnSeconds + 1;
            if (failedSpawnSeconds >= 90) {
                stop("ABORTED: no valid spawn site found for 90 seconds in " + anchor.getWorld().getEnvironment() + ".");
                return;
            }
        } else failedSpawnSeconds = 0;
        if (pending == 0 && mobs.isEmpty()) {
            if (wave >= totalWaves) stop("VICTORY: final wave cleared");
            else {
                state = SiegeState.INTERMISSION;
                seconds = plugin.getConfig().getInt("game.intermission-seconds", 10);
                broadcast("Wave " + wave + " cleared! Next wave in " + seconds + " seconds.");
            }
        }
    }

    /**
     * Drops the mobs that are really gone and keeps the living ones.
     *
     * <p>A mob that cannot be found is only dropped when the chunk it was last
     * seen in is loaded as well: then it is really gone - killed by an admin
     * with /kill, killed by a player or removed by another plugin. A mob that
     * waits in an unloaded chunk is alive and keeps the wave running.
     */
    private void dropGoneMobs() {
        Map<UUID, Boolean> gone = new HashMap<>();
        for (UUID id : mobs) {
            Entity entity = Bukkit.getEntity(id);
            Location last = mobPositions.get(id);
            boolean lastChunkLoaded = last != null && last.getWorld() != null
                    && last.getWorld().isChunkLoaded(last.getBlockX() >> 4, last.getBlockZ() >> 4);
            gone.put(id, MobLedger.gone(entity != null, entity != null && entity.isDead(), lastChunkLoaded));
        }
        MobLedger.removeGone(mobs, gone);
    }

    /**
     * A mob that left the world without dying - an admin's /kill, another
     * plugin - does not count towards the wave any more. Without this the wave
     * could never be cleared: the counter kept an enemy that no longer exists.
     */
    public void onMobRemoved(Entity entity, EntityRemoveEvent.Cause cause) {
        UUID id = entity.getUniqueId();
        mobPositions.remove(id);
        if (!mobs.remove(id)) return;
        // A dead mob was already reported by the damage handler. Only a mob
        // that vanished without dying is worth a line in the log - and only
        // while the match runs: at the end the mobs are removed on purpose.
        if (running() && !entity.isDead()) {
            plugin.getLogger().info("Siege: a " + entity.getType()
                    + " left the wave without dying (" + cause + ").");
        }
    }

    /**
     * Restrict natural-surface spawning to loaded, previously generated chunks.
     * Scanning vertically around the anchor is necessary in Nether caves and End islands:
     * getHighestBlockAt() would select the Nether roof or empty End void.
     */
    /**
     * Enemies spawn close to the anchor, on its level and in the same kind of
     * environment: a surface anchor gets surface spawns, a cave anchor gets
     * cave spawns. Spawning far away or on another level left mobs stuck in
     * caves that the player could neither see nor reach.
     */
    private boolean spawnEnemy() {
        if (waveTypes.isEmpty()) return false;
        int min = plugin.getConfig().getInt("arena.spawn-min-distance", 20);
        int max = plugin.getConfig().getInt("arena.spawn-max-distance", 55);
        // Never spawn outside the arena: the player has to be able to reach them.
        max = Math.min(max, plugin.getConfig().getInt("arena.movement-radius", 60));
        if (min < 8 || max < min) return false;
        World world = Objects.requireNonNull(anchor.getWorld());
        boolean wantSurface = openSkyAbove(world, anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ());
        Location fallback = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = anchor.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = anchor.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location spot = findSpawnSpot(world, x, z, wantSurface);
            if (spot == null) continue;
            // Prefer a spot in the direction the player is looking, so the wave
            // arrives where it is being watched.
            if (!inFrontOfPlayer(spot) && fallback == null) {
                fallback = spot;
                continue;
            }
            return spawnAt(spot);
        }
        return fallback != null && spawnAt(fallback);
    }

    /** Spawns one enemy of the type that is due next in the wave's mix. */
    private boolean spawnAt(Location spot) {
        MobType type = MobType.pick(waveTypes, spawnCursor, spawnedPerType);
        if (type == null) return false;
        Entity entity;
        try {
            entity = spot.getWorld().spawnEntity(spot, MobTypes.entityType(type));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Siege: cannot spawn " + type.display() + ": " + ex.getMessage());
            return false;
        }
        if (!(entity instanceof Mob mob)) { entity.remove(); return false; }
        configureMob(mob);
        mobs.add(entity.getUniqueId());
        // The spawn spot is the first known position of the mob. Without it a
        // mob that leaves the loaded area before it was ever seen could not be
        // found and its chunk could not be held.
        mobPositions.put(entity.getUniqueId(), spot.clone());
        spawnedPerType.merge(type, 1, Integer::sum);
        spawnCursor++;
        spawned++;
        return true;
    }

    /**
     * A standable spot close to the anchor's level. A spot under open sky is
     * only accepted when the anchor is in the open as well, and vice versa,
     * so enemies never appear in a cave while the player fights in the open.
     */
    private Location findSpawnSpot(World world, int x, int z, boolean wantSurface) {
        int anchorY = anchor.getBlockY();
        int range = Math.max(2, plugin.getConfig().getInt("arena.spawn-vertical-range", 8));
        Location fallback = null;
        for (int d = 0; d <= range; d++) {
            int high = anchorY + d;
            int low = anchorY - d;
            for (int y : (d == 0 ? new int[]{high} : new int[]{high, low})) {
                if (!safeStand(world, x, y, z)) continue;
                Location spot = new Location(world, x + .5, y, z + .5);
                if (openSkyAbove(world, x, y, z) == wantSurface) return spot;
                if (fallback == null) fallback = spot;
            }
        }
        return fallback;
    }

    /** True when nothing stands between the spot and the sky. */
    private static boolean openSkyAbove(World world, int x, int y, int z) {
        return world.getHighestBlockYAt(x, z) <= y + 1;
    }

    /** True when the spot lies in the direction the nearest player is looking. */
    private boolean inFrontOfPlayer(Location spot) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : livingOnline()) {
            double distance = player.getLocation().distanceSquared(spot);
            if (distance < best) { best = distance; nearest = player; }
        }
        if (nearest == null) return true;
        org.bukkit.util.Vector look = nearest.getEyeLocation().getDirection().setY(0).normalize();
        org.bukkit.util.Vector to = spot.toVector().subtract(nearest.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1) return true;
        return to.normalize().dot(look) >= 0;
    }

    private static boolean safeStand(World world, int x, int feetY, int z) {
        if (feetY < world.getMinHeight() + 1 || feetY + 1 >= world.getMaxHeight()) return false;
        Block floor = world.getBlockAt(x, feetY - 1, z);
        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        Material type = floor.getType();
        if (!type.isSolid() || type == Material.MAGMA_BLOCK || type == Material.CACTUS
                || type == Material.BEDROCK || type.name().contains("PORTAL")) return false;
        return floor.getBoundingBox().getHeight() > 0.75
                && feet.getType().isAir() && head.getType().isAir();
    }

    private static boolean hasSafeFooting(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        int y = location.getBlockY();
        return safeStand(world, location.getBlockX(), y, location.getBlockZ());
    }

    /**
     * Hold the arena loaded. An enemy in an unticketed chunk is unloaded
     * within seconds: it stops moving, cannot be glowing and cannot be found.
     * The whole arena is held - not only the spawn ring - so that a mob that
     * wanders off still moves towards the players.
     */
    private void holdSpawnChunks() {
        World world = Objects.requireNonNull(anchor.getWorld());
        int min = plugin.getConfig().getInt("arena.spawn-min-distance", 20);
        int max = Math.min(plugin.getConfig().getInt("arena.spawn-max-distance", 55),
                plugin.getConfig().getInt("arena.movement-radius", 60));
        int combat = Math.max(max, plugin.getConfig().getInt("arena.combat-radius", 80));
        int anchorX = anchor.getBlockX(), anchorZ = anchor.getBlockZ();
        int firstX = (anchorX - combat) >> 4, lastX = (anchorX + combat) >> 4;
        int firstZ = (anchorZ - combat) >> 4, lastZ = (anchorZ + combat) >> 4;
        int ticketed = 0;
        for (int chunkX = firstX; chunkX <= lastX; chunkX++) {
            for (int chunkZ = firstZ; chunkZ <= lastZ; chunkZ++) {
                if (!WaveMath.chunkIntersectsCircle(chunkX, chunkZ, anchorX, anchorZ, combat)) continue;
                if (!world.isChunkGenerated(chunkX, chunkZ)) continue;
                if (world.addPluginChunkTicket(chunkX, chunkZ, plugin)) ticketed++;
            }
        }
        world.addPluginChunkTicket(anchorX >> 4, anchorZ >> 4, plugin);
        plugin.getLogger().info("Siege: holding " + ticketed + " arena chunks around the anchor.");
    }

    /**
     * Mobs that fall into the void or end up in lava are brought back to the
     * arena instead of dying there. Environmental damage is cancelled for
     * siege mobs, so without this they would sink out of the world.
     *
     * <p>A mob that left the arena is brought back as well. Outside the arena
     * it is out of the player's sight and out of the server's activation
     * range: it stands still, waits for a player that never comes and the wave
     * cannot be finished.
     */
    private void rescueEndangeredMobs() {
        World world = anchor.getWorld();
        double floor = world.getMinHeight() - 2;
        int arena = plugin.getConfig().getInt("arena.combat-radius", 80);
        for (UUID id : mobs) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || entity.isDead()) continue;
            Location at = entity.getLocation();
            boolean inVoid = at.getY() <= floor;
            boolean inLava = at.getBlock().getType() == Material.LAVA
                    || at.clone().add(0, -1, 0).getBlock().getType() == Material.LAVA;
            boolean outsideArena = Math.hypot(at.getX() - anchor.getX(), at.getZ() - anchor.getZ()) > arena;
            if (!inVoid && !inLava && !outsideArena) continue;
            entity.teleport(anchor);
            plugin.getLogger().info("Siege: rescued a " + entity.getType()
                    + (inVoid ? " from the void" : inLava ? " from lava" : " from outside the arena")
                    + ".");
        }
    }

    /**
     * The sun - or an ignited arrow - can set a siege mob on fire again after
     * it was configured. Never let a siege mob burn.
     */
    private void extinguishMobs() {
        for (UUID id : mobs) {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity living)) continue;
            if (living.getFireTicks() <= 0 && !living.isVisualFire()) continue;
            living.setFireTicks(0);
            living.setVisualFire(false);
        }
    }

    /**
     * Enemies of a wave that runs long - usually stuck in a cave or behind
     * terrain - start glowing so the wave cannot stall unnoticed. The admin
     * can force the same glow on demand with /siege glow.
     */
    private void applyLongWaveGlow() {
        boolean automatic = automaticGlow();
        if (automatic && !glowAnnounced) {
            glowAnnounced = true;
            int timeout = Math.max(120, plugin.getConfig().getInt("waves.max-wave-seconds", 1200));
            broadcast("Wave " + wave + " has been running for " + (waveSeconds / 60)
                    + " minutes - the remaining enemies are glowing. The wave aborts in "
                    + Math.max(0, (timeout - waveSeconds) / 60) + " minutes.");
        }
        applyGlow(automatic);
    }

    /** True when the wave has run long enough for the automatic glow. */
    private boolean automaticGlow() {
        int threshold = plugin.getConfig().getInt("waves.glow-after-seconds", 300);
        return threshold > 0 && waveSeconds > threshold;
    }

    /**
     * Gives every living siege mob the glow effect, or takes it away again
     * when neither the automatic nor the forced glow applies.
     */
    private void applyGlow(boolean automatic) {
        for (UUID id : mobs) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity living)) continue;
            if (forceGlow || automatic) living.addPotionEffect(GLOW, true);
            else living.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    /**
     * Forces the siege mobs to glow, so a wave that is nearly cleared can be
     * finished without waiting for the automatic glow. The forced glow only
     * lasts for the wave it was switched on in.
     */
    public String setGlow(boolean value) {
        if (!running()) return "No match is active.";
        forceGlow = value;
        applyGlow(automaticGlow());
        if (!value) return "Siege mobs stopped glowing (" + mobs.size() + " enemies alive).";
        // A mob in an unloaded chunk cannot be seen at all, not even with the
        // glow: hold its chunk so it comes back and can be hunted down.
        recoverMobChunks();
        return "Siege mobs are glowing (" + mobs.size() + " enemies alive)." + mobLocations();
    }

    /**
     * Holds the chunks of the mobs that left the loaded area. An unloaded mob
     * does not tick, so it neither moves towards the player nor glows.
     */
    private void recoverMobChunks() {
        World world = anchor == null ? null : anchor.getWorld();
        if (world == null) return;
        int held = 0;
        for (Location at : mobPositions.values()) {
            if (at.getWorld() == null || !at.getWorld().equals(world)) continue;
            if (world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            if (!world.isChunkGenerated(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            if (world.addPluginChunkTicket(at.getBlockX() >> 4, at.getBlockZ() >> 4, plugin)) held++;
        }
        if (held > 0) plugin.getLogger().info("Siege: holding " + held
                + " chunks of mobs that left the loaded area.");
    }

    /**
     * Where the remaining enemies are. A glowing mob that hides behind a hill
     * is still hard to find, so the admin gets the coordinates.
     */
    private String mobLocations() {
        return mobLocations(8);
    }

    /**
     * The same list, but shorter.
     *
     * @param limit how many mobs are named
     */
    private String mobLocations(int limit) {
        if (mobs.isEmpty()) return "";
        StringBuilder text = new StringBuilder(" Remaining:");
        int shown = 0;
        for (UUID id : mobs) {
            if (shown >= limit) {
                text.append(" ...");
                break;
            }
            Entity entity = Bukkit.getEntity(id);
            Location at = entity != null ? entity.getLocation() : mobPositions.get(id);
            if (at == null) {
                text.append(" unknown");
                shown++;
                continue;
            }
            String name = entity != null
                    ? entity.getType().name().toLowerCase(Locale.ROOT) : "mob";
            text.append(' ').append(name).append(" @ ")
                    .append(at.getWorld() == null ? "?" : at.getWorld().getName())
                    .append(' ').append(at.getBlockX()).append(',').append(at.getBlockY())
                    .append(',').append(at.getBlockZ());
            shown++;
        }
        return text.toString();
    }

    /**
     * Remembers where the living mobs are, so the admin can be told where the
     * last enemy hides - and so a mob that left the loaded area can be found
     * again.
     */
    private void rememberMobPositions() {
        for (UUID id : mobs) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid() || entity.isDead()) continue;
            mobPositions.put(id, entity.getLocation());
        }
    }

    /**
     * True when a stuck mob may dig through anything. After a long wave the
     * mobs tear through the walls instead of standing in front of them - no
     * matter which wave it is and no matter which tool they carry.
     */
    public boolean desperateMining() {
        int seconds = plugin.getConfig().getInt("ai.desperate-after-seconds", 600);
        if (seconds < 0) return true;
        if (seconds == 0) return false;
        return waveSeconds >= seconds;
    }

    /** How much slower a desperate break is than a normal one. */
    public double desperateBreakFactor() {
        return Math.max(1.0, plugin.getConfig().getDouble("ai.desperate-break-factor", 2.0));
    }

    /** The seconds the current wave has been running. */
    public int waveSeconds() { return waveSeconds; }

    /** Toggles the forced glow. */
    public String toggleGlow() {
        return setGlow(!forceGlow);
    }

    private void configureMob(Mob mob) {
        mob.getPersistentDataContainer().set(mobTag, PersistentDataType.BYTE, (byte) 1);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            // The vanilla door breaking would destroy a door without a journal
            // entry. The plugin opens doors instead and only ever breaks
            // blocks through the journalled mining path.
            zombie.setCanBreakDoors(false);
        }
        if (mob instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
        if (mob instanceof PiglinAbstract piglin) {
            // A piglin turns into a zombified piglin outside the Nether. A siege
            // mob stays what it is, in every dimension.
            piglin.setImmuneToZombification(true);
        }
        // The mob can already be smouldering when it is configured, and the sun
        // can set it on fire again after a chunk reload - clear both.
        mob.setFireTicks(0);
        mob.setVisualFire(false);
        AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            double base = maxHealth.getBaseValue();
            double scaled = Math.max(1, base * WaveMath.healthMultiplier(wave, totalWaves));
            maxHealth.setBaseValue(scaled);
            mob.setHealth(scaled);
        }
        AttributeInstance attack = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) attack.setBaseValue(attack.getBaseValue() * (1.0 + 0.025 * (wave - 1)));
        mob.getPathfinder().setCanOpenDoors(true);
        mob.getPathfinder().setCanPassDoors(true);
        EntityEquipment eq = mob.getEquipment();
        if (eq != null) {
            Material main = siegeMainHand(mob, wave);
            if (main != null) {
                eq.setItemInMainHand(new ItemStack(main));
                eq.setItemInMainHandDropChance(0.0f);
            }
            if (carriesShield(mob) && random.nextDouble() < shieldChance()) {
                eq.setItemInOffHand(new ItemStack(Material.SHIELD));
                eq.setItemInOffHandDropChance(0.0f);
            }
            if (wave >= 12 && random.nextDouble() < Math.min(0.6, wave * 0.02)) {
                eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                eq.setHelmetDropChance(0.0f);
            }
        }
    }

    /**
     * The gear a siege mob carries in its main hand. It is both its weapon and
     * its mining tool: an axe breaks wooden walls and hits hard, a sword hits
     * even harder, a pickaxe breaks stone. The ranged types carry their bow or
     * crossbow and shoot with it.
     */
    private Material siegeMainHand(Mob mob, int wave) {
        return switch (mob.getType()) {
            // From wave 3 a zombie always carries a tool or a weapon.
            case ZOMBIE -> wave >= 3 ? handItem(wave, true) : handItem(wave, false);
            case VINDICATOR -> Material.IRON_AXE;
            case PIGLIN_BRUTE -> Material.NETHERITE_AXE;
            case WITHER_SKELETON -> random.nextDouble() < equipmentChance()
                    ? Material.STONE_SWORD
                    : null;
            case SKELETON -> Material.BOW;
            case PILLAGER -> Material.CROSSBOW;
            // A creeper explodes and a blaze throws fireballs: a sword in their
            // hand would be pointless.
            default -> null;
        };
    }

    /**
     * A random weapon or tool for a melee mob. The axe stays the most common
     * one, because a wooden wall is the most common obstacle.
     *
     * @param certain true when the mob always gets something
     */
    private Material handItem(int wave, boolean certain) {
        if (!randomEquipment()) return null;
        if (!certain && random.nextDouble() > equipmentChance()) return null;
        double roll = random.nextDouble();
        if (roll < 0.4) return Material.IRON_AXE;
        if (roll < 0.75) return wave >= 5 ? Material.IRON_SWORD : Material.STONE_SWORD;
        return Material.IRON_PICKAXE;
    }

    /** Only the melee types carry a shield; they raise it while they fight. */
    private boolean carriesShield(Mob mob) {
        return switch (mob.getType()) {
            case ZOMBIE, VINDICATOR, WITHER_SKELETON, PIGLIN_BRUTE -> true;
            default -> false;
        };
    }

    private boolean randomEquipment() {
        return plugin.getConfig().getBoolean("mobs.random-equipment", true);
    }

    private double equipmentChance() {
        return Math.max(0, Math.min(1, plugin.getConfig().getDouble("mobs.equipment-chance", 0.45)));
    }

    private double shieldChance() {
        return Math.max(0, Math.min(1, plugin.getConfig().getDouble("mobs.shield-chance", 0.25)));
    }

    public void eliminate(Player player) {
        PlayerRecord record = players.get(player.getUniqueId());
        if (record != null && record.alive()) {
            record.eliminate();
            broadcast(player.getName() + " has been eliminated.");
            player.sendMessage("You are out of the siege. You keep watching as a spectator until the match ends.");
        }
    }

    public void disconnected(Player player) {
        PlayerRecord record = players.get(player.getUniqueId());
        if (record != null) record.disconnect(System.currentTimeMillis() / 1000);
    }

    public void joined(Player player) {
        if (!running()) return;
        PlayerRecord record = players.get(player.getUniqueId());
        if (record == null) {
            PlayerRecord visitor = new PlayerRecord(player);
            visitors.put(player.getUniqueId(), visitor);
            recovery.remember(visitor);
            // A late arrival stays out of the fight: the spectator mode already
            // keeps them harmless, this only moves them out of the arena.
            keepOutOfArena(player);
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("A siege is running: wave " + wave + "/" + totalWaves + ", "
                    + livingCount() + (livingCount() == 1 ? " player" : " players")
                    + " still in the fight. You are watching as a spectator until the match ends.");
        } else {
            if (record.disconnectedAt() >= 0 &&
                    System.currentTimeMillis() / 1000 - record.disconnectedAt()
                    >= plugin.getConfig().getInt("game.reconnect-grace-seconds", 60)) record.eliminate();
            record.reconnect();
            player.teleport(anchor);
            player.setGameMode(record.alive() ? GameMode.SURVIVAL : GameMode.SPECTATOR);
        }
    }

    /** The participants that are still alive, including the ones that are
     *  briefly offline but within the reconnect grace period. */
    public int livingCount() {
        int count = 0;
        for (PlayerRecord record : players.values()) if (record.alive()) count++;
        return count;
    }

    /** Moves a late arrival out of the arena. The world spawn is used when it
     *  lies outside the boundary; otherwise the player simply stays where the
     *  server put them. */
    private void keepOutOfArena(Player player) {
        if (anchor == null || outside(player.getLocation())) return;
        Location spawn = player.getWorld().getSpawnLocation();
        if (outside(spawn)) player.teleport(spawn);
    }

    public boolean outside(Location loc) {
        if (anchor == null || loc.getWorld() != anchor.getWorld()) return true;
        return WaveMath.outside(loc.getX(), loc.getZ(), anchor.getX(), anchor.getZ(), radius);
    }

    public boolean insideCombat(Location loc) {
        if (!running() || anchor == null || loc.getWorld() != anchor.getWorld()) return false;
        int r = plugin.getConfig().getInt("arena.combat-radius", 80);
        return !WaveMath.outside(loc.getX(), loc.getZ(), anchor.getX(), anchor.getZ(), r);
    }

    public boolean insideDestruction(Location loc) {
        if (!combat() || anchor == null || loc.getWorld() != anchor.getWorld()) return false;
        int r = plugin.getConfig().getInt("arena.destruction-radius", 60);
        return !WaveMath.outside(loc.getX(), loc.getZ(), anchor.getX(), anchor.getZ(), r);
    }

    public Collection<Player> livingOnline() {
        List<Player> living = new ArrayList<>();
        for (PlayerRecord record : players.values()) {
            Player player = Bukkit.getPlayer(record.id());
            if (record.alive() && player != null && player.isOnline() && !player.isDead())
                living.add(player);
        }
        return living;
    }

    public String status() {
        if (!running()) return "No active siege. Anchor: "
                + (draftAnchor == null ? "not set" : coordinates(draftAnchor));
        long living = players.values().stream().filter(PlayerRecord::alive).count();
        String types = "none";
        for (MobType type : waveTypes) {
            types = types.equals("none") ? type.display() : types + ", " + type.display();
        }
        return "State " + state + " | Wave " + wave + "/" + totalWaves
                + " | Living " + living + "/" + initialPlayers + " | Remaining mobs "
                + (pending + mobs.size()) + " | Boundary " + radius + " | Types " + types
                + " | Mining " + miningState() + " | Glow " + (forceGlow ? "forced (this wave)" : "auto")
                + mobLocations(3);
    }

    /** A short description of what the siege mobs are allowed to break. */
    private String miningState() {
        if (!plugin.getConfig().getBoolean("mining.enabled", false)) return "off";
        String protection = plugin.protectionPlugin();
        if (protection != null && plugin.getConfig().getBoolean("mining.deny-if-protection-plugin-present", true))
            return "off (" + protection + " detected)";
        return desperateMining() ? "on (desperate)" : "on";
    }

    public void stop(String reason) {
        if (!running()) return;
        state = SiegeState.IDLE; // must precede teleport/mode restoration to disable event restrictions
        broadcast(reason);
        // A copy: removing a mob fires the remove event, which touches the set.
        for (UUID id : Set.copyOf(mobs)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        mobs.clear();
        mobPositions.clear();
        if (chunksHeld && anchor != null && anchor.getWorld() != null) {
            anchor.getWorld().removePluginChunkTickets(plugin);
            chunksHeld = false;
        }
        if (plugin.getConfig().getBoolean("arena.rollback-mob-damage", true)) blockJournal.restore();
        for (PlayerRecord record : players.values()) restore(record);
        for (PlayerRecord record : visitors.values()) restore(record);
        players.clear(); visitors.clear();
        pending = 0; wave = 0; seconds = 0; spawned = 0; anchor = null;
        forceGlow = false;
    }

    public void recoverIfNeeded(Player player) {
        if (!running()) recovery.restore(player);
    }

    private void restore(PlayerRecord record) {
        Player player = Bukkit.getPlayer(record.id());
        if (player != null && player.isOnline()) recovery.restore(player);
        // Dead/offline players are restored on post-respawn or next join.
    }

    private void broadcast(String text) { Bukkit.broadcastMessage("[Siege] " + text); }

    /** Server-wide chat message with the standard prefix. */
    public void announce(String text) { broadcast(text); }
    private String coordinates(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }
}
