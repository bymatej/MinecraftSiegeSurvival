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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;

import java.util.*;

/**
 * One server-wide session. All calls must run on the main Paper server thread.
 * No global world rules or borders are mutated.
 */
public final class SiegeGame {
    private final SiegePlugin plugin;
    private final NamespacedKey mobTag;
    private final RecoveryStore recovery;
    private final BlockJournal blockJournal;
    private final Random random = new Random();
    private final LinkedHashMap<UUID, PlayerRecord> players = new LinkedHashMap<>();
    private final Map<UUID, PlayerRecord> visitors = new LinkedHashMap<>();
    private final Set<UUID> mobs = new HashSet<>();
    private final EnumSet<EntityType> allowed = EnumSet.noneOf(EntityType.class);
    private SiegeState state = SiegeState.IDLE;
    private Location draftAnchor;
    private Location anchor;
    private int radius;
    private int totalWaves;
    private int wave;
    private int seconds;
    private int pending;
    private int initialPlayers;
    private int noLivingSeconds;

    public SiegeGame(SiegePlugin plugin) {
        this.plugin = plugin;
        this.recovery = new RecoveryStore(plugin);
        this.blockJournal = new BlockJournal(plugin);
        this.blockJournal.restore(); // Recover uncommitted mob damage after server restart.
        this.mobTag = new NamespacedKey(plugin, "siege_match_mob");
        this.radius = plugin.getConfig().getInt("arena.movement-radius", 200);
        if (plugin.getConfig().getBoolean("mobs.zombie", true)) allowed.add(EntityType.ZOMBIE);
        if (plugin.getConfig().getBoolean("mobs.skeleton", true)) allowed.add(EntityType.SKELETON);
        if (plugin.getConfig().getBoolean("mobs.pillager", true)) allowed.add(EntityType.PILLAGER);
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
    public int radius() { return radius; }
    public Location anchor() { return anchor == null ? null : anchor.clone(); }
    public boolean journalMobBreak(Block block) {
        return !plugin.getConfig().getBoolean("arena.rollback-mob-damage", true) || blockJournal.record(block);
    }
    public boolean isSiegeMob(Entity e) { return mobs.contains(e.getUniqueId()); }
    public Set<UUID> mobIds() { return Set.copyOf(mobs); }
    public boolean enrolled(UUID uuid) { return players.containsKey(uuid) || visitors.containsKey(uuid); }
    public boolean alive(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        return record != null && record.alive();
    }
    public boolean eliminated(UUID uuid) { return enrolled(uuid) && !alive(uuid); }
    public boolean allowed(EntityType type) { return allowed.contains(type); }

    public String toggle(EntityType type) {
        if (running()) return "Cannot edit mobs during a match.";
        if (type != EntityType.ZOMBIE && type != EntityType.SKELETON && type != EntityType.PILLAGER)
            return "Unsupported mob.";
        if (!allowed.remove(type)) allowed.add(type);
        return type.name() + (allowed.contains(type) ? " enabled" : " disabled");
    }

    public String setRadius(int value) {
        if (running()) return "Cannot change the radius while a match is running.";
        int max = plugin.getConfig().getInt("arena.combat-radius", 220);
        if (value < 25 || value > max) return "Radius must be between 25 and " + max + " blocks.";
        radius = value;
        return "Siege boundary set to " + radius + " blocks.";
    }

    public String saveAnchor(Player initiator) {
        if (running()) return "Anchor cannot be changed during a match.";
        if (initiator.getWorld().getEnvironment() != World.Environment.NORMAL)
            return "The anchor must be in the Overworld.";
        draftAnchor = initiator.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN)
                .getLocation().add(0.5, 1, 0.5);
        return "Anchor saved at " + coordinates(draftAnchor) + ".";
    }

    public String start(Player initiator, int requestedWaves, int prepMinutes) {
        if (running()) return "A siege is already in progress.";
        if (allowed.isEmpty()) return "Enable at least one mob in /siege setup.";
        int max = plugin.getConfig().getInt("game.max-waves", 100);
        if (requestedWaves < 1 || requestedWaves > max) return "Wave count must be 1-" + max + ".";
        if (prepMinutes < 0 || prepMinutes > 240) return "Preparation must be between 0 and 240 minutes.";
        if (initiator.getWorld().getEnvironment() != World.Environment.NORMAL)
            return "Start a siege in the Overworld.";
        if (initiator.getWorld().getDifficulty() == Difficulty.PEACEFUL)
            return "Peaceful difficulty cannot support a siege. Change the world's difficulty first.";
        if (Bukkit.getOnlinePlayers().isEmpty()) return "No players are online.";
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
        players.clear();
        visitors.clear();
        mobs.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerRecord record = new PlayerRecord(player);
            players.put(player.getUniqueId(), record);
            recovery.remember(record);
        }
        initialPlayers = players.size();
        state = prepMinutes > 0 ? SiegeState.PREPARING : SiegeState.INTERMISSION;
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
        wave++;
        state = SiegeState.WAVE;
        seconds = 0;
        if (wave == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (enrolled(player.getUniqueId()) && outside(player.getLocation())) player.teleport(anchor);
            }
        }
        int count = WaveMath.enemies(wave, initialPlayers,
                plugin.getConfig().getInt("waves.max-enemies-per-wave", 120),
                plugin.getConfig().getDouble("waves.base-enemies", 5),
                plugin.getConfig().getDouble("waves.growth-per-wave", 1.3),
                plugin.getConfig().getDouble("waves.player-scaling", 0.45));
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
            if (seconds == 30 || seconds == 10 || (seconds <= 5 && seconds > 0))
                broadcast("Wave 1 in " + seconds + " seconds. Return to base!");
            if (--seconds <= 0) beginWave();
            return;
        }
        if (state == SiegeState.INTERMISSION) {
            if (--seconds <= 0) beginWave();
            return;
        }
        mobs.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        int batch = plugin.getConfig().getInt("waves.spawn-batch-size", 5);
        int limit = plugin.getConfig().getInt("waves.max-active-mobs", 40);
        int attempts = Math.min(Math.min(batch, pending), Math.max(0, limit - mobs.size()));
        for (int i = 0; i < attempts; i++) {
            if (spawnEnemy()) pending--;
            else break; // invalid spawn positions: try again next second rather than spawning in solids
        }
        if (pending == 0 && mobs.isEmpty()) {
            if (wave >= totalWaves) stop("VICTORY: final wave cleared");
            else {
                state = SiegeState.INTERMISSION;
                seconds = plugin.getConfig().getInt("game.intermission-seconds", 30);
                broadcast("Wave " + wave + " cleared! Next wave in " + seconds + " seconds.");
            }
        }
    }

    private boolean spawnEnemy() {
        List<EntityType> types = new ArrayList<>(allowed);
        if (types.isEmpty()) return false;
        int min = plugin.getConfig().getInt("arena.spawn-min-distance", 40);
        int max = plugin.getConfig().getInt("arena.spawn-max-distance", 72);
        if (max < min || min < 8 || max >= plugin.getConfig().getInt("arena.combat-radius", 220))
            return false;
        World world = Objects.requireNonNull(anchor.getWorld());
        for (int attempt = 0; attempt < 18; attempt++) {
            double direction = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * Math.max(1, max - min);
            int x = anchor.getBlockX() + (int) Math.round(Math.cos(direction) * distance);
            int z = anchor.getBlockZ() + (int) Math.round(Math.sin(direction) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue; // never synchronously generate terrain
            Block floor = world.getHighestBlockAt(x, z);
            Block feet = floor.getRelative(org.bukkit.block.BlockFace.UP);
            if (!floor.getType().isSolid() || !feet.isPassable()
                    || !feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) continue;
            Location spot = feet.getLocation().add(0.5, 0, 0.5);
            Entity entity = world.spawnEntity(spot, types.get(random.nextInt(types.size())));
            if (!(entity instanceof Mob mob)) { entity.remove(); return false; }
            configureMob(mob);
            mobs.add(entity.getUniqueId());
            return true;
        }
        return false;
    }

    private void configureMob(Mob mob) {
        mob.getPersistentDataContainer().set(mobTag, PersistentDataType.BYTE, (byte) 1);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        if (mob instanceof Zombie zombie) { zombie.setShouldBurnInDay(false); zombie.setCanBreakDoors(true); }
        if (mob instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
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
            if (mob instanceof Zombie && wave >= 6) {
                Material tool = wave >= 11 && random.nextBoolean() ? Material.IRON_PICKAXE : Material.IRON_AXE;
                eq.setItemInMainHand(new ItemStack(tool));
                eq.setItemInMainHandDropChance(0.0f);
            }
            if (wave >= 12 && random.nextDouble() < Math.min(0.6, wave * 0.02)) {
                eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                eq.setHelmetDropChance(0.0f);
            }
        }
    }

    public void eliminate(Player player) {
        PlayerRecord record = players.get(player.getUniqueId());
        if (record != null && record.alive()) {
            record.eliminate();
            broadcast(player.getName() + " has been eliminated.");
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
            player.teleport(anchor);
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("Siege already active. You are spectating until the next match.");
        } else {
            if (record.disconnectedAt() >= 0 &&
                    System.currentTimeMillis() / 1000 - record.disconnectedAt()
                    >= plugin.getConfig().getInt("game.reconnect-grace-seconds", 60)) record.eliminate();
            record.reconnect();
            player.teleport(anchor);
            player.setGameMode(record.alive() ? GameMode.SURVIVAL : GameMode.SPECTATOR);
        }
    }

    public boolean outside(Location loc) {
        if (anchor == null || loc.getWorld() != anchor.getWorld()) return true;
        return WaveMath.outside(loc.getX(), loc.getZ(), anchor.getX(), anchor.getZ(), radius);
    }

    public boolean insideCombat(Location loc) {
        if (!running() || anchor == null || loc.getWorld() != anchor.getWorld()) return false;
        int r = plugin.getConfig().getInt("arena.combat-radius", 220);
        return !WaveMath.outside(loc.getX(), loc.getZ(), anchor.getX(), anchor.getZ(), r);
    }

    public boolean insideDestruction(Location loc) {
        if (!combat() || anchor == null || loc.getWorld() != anchor.getWorld()) return false;
        int r = plugin.getConfig().getInt("arena.destruction-radius", 64);
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
        return "State " + state + " | Wave " + wave + "/" + totalWaves
                + " | Living " + living + "/" + initialPlayers + " | Remaining mobs "
                + (pending + mobs.size()) + " | Boundary " + radius;
    }

    public void stop(String reason) {
        if (!running()) return;
        state = SiegeState.IDLE; // must precede teleport/mode restoration to disable event restrictions
        broadcast(reason);
        for (UUID id : mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        mobs.clear();
        if (plugin.getConfig().getBoolean("arena.rollback-mob-damage", true)) blockJournal.restore();
        for (PlayerRecord record : players.values()) restore(record);
        for (PlayerRecord record : visitors.values()) restore(record);
        players.clear(); visitors.clear();
        pending = 0; wave = 0; seconds = 0; anchor = null;
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
    private String coordinates(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }
}
