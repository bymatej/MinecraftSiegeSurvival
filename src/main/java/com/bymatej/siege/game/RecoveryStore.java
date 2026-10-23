package com.bymatej.siege.game;

import com.bymatej.siege.SiegePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Durable snapshot of game mode/location, including offline and dead players. */
public final class RecoveryStore {
    private record Original(String world, double x, double y, double z, float yaw, float pitch, GameMode mode) {
        Location resolve() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }
    private final SiegePlugin plugin;
    private final File file;
    private final Map<UUID, Original> originals = new HashMap<>();

    public RecoveryStore(SiegePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recovery.yml");
        load();
    }
    public void remember(PlayerRecord record) {
        Location l = record.before();
        if (l.getWorld() == null) return;
        originals.putIfAbsent(record.id(), new Original(l.getWorld().getName(), l.getX(), l.getY(),
                l.getZ(), l.getYaw(), l.getPitch(), record.gameMode()));
        save();
    }
    public boolean restore(Player player) {
        Original original = originals.get(player.getUniqueId());
        if (original == null || player.isDead()) return false;
        Location location = original.resolve();
        if (location == null) {
            plugin.getLogger().warning("Cannot restore player: world not loaded: " + original.world());
            return false;
        }
        player.leaveVehicle();
        if (!player.teleport(location)) return false;
        player.setGameMode(original.mode());
        originals.remove(player.getUniqueId());
        save();
        return true;
    }
    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String prefix = "players." + key + ".";
                String world = yaml.getString(prefix + "world");
                if (world == null || world.isBlank()) continue;
                GameMode mode = GameMode.valueOf(yaml.getString(prefix + "mode", "SURVIVAL"));
                originals.put(id, new Original(world, yaml.getDouble(prefix + "x"),
                        yaml.getDouble(prefix + "y"), yaml.getDouble(prefix + "z"),
                        (float) yaml.getDouble(prefix + "yaw"), (float) yaml.getDouble(prefix + "pitch"), mode));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping malformed recovery entry: " + key);
            }
        }
        if (!originals.isEmpty()) plugin.getLogger().warning(
                "Loaded " + originals.size() + " pending player state restoration(s).");
    }
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : originals.entrySet()) {
            String prefix = "players." + entry.getKey() + ".";
            Original o = entry.getValue();
            yaml.set(prefix + "world", o.world());
            yaml.set(prefix + "x", o.x());
            yaml.set(prefix + "y", o.y());
            yaml.set(prefix + "z", o.z());
            yaml.set(prefix + "yaw", o.yaw());
            yaml.set(prefix + "pitch", o.pitch());
            yaml.set(prefix + "mode", o.mode().name());
        }
        try {
            if (!plugin.getDataFolder().isDirectory()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save player recovery journal: " + ex.getMessage());
        }
    }
}
