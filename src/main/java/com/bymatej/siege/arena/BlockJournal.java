package com.bymatej.siege.arena;

import com.bymatej.siege.SiegePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Durable journal of simple (non-tile-entity) blocks broken by siege mobs.
 *
 * Append-and-flush a snapshot BEFORE modifying the world. Rollback restores
 * only if the target block is still AIR, to avoid overwriting player rebuilds.
 * This is best-effort, not a replacement for full world backups.
 */
public final class BlockJournal {
    private final SiegePlugin plugin;
    private final Path file;

    public BlockJournal(SiegePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("mob-block-journal.tsv");
    }

    /** Returns false if the original state cannot be safely persisted. */
    public boolean record(Block block) {
        String blockData = block.getBlockData().getAsString();
        String encoded = Base64.getEncoder().encodeToString(blockData.getBytes(StandardCharsets.UTF_8));
        String line = block.getWorld().getUID() + "\t" + block.getX() + "\t"
                + block.getY() + "\t" + block.getZ() + "\t" + encoded + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Refusing mob block break: cannot journal "
                    + block.getLocation() + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Called on match cleanup and after server restart.
     * Replays in reverse order and preserves entries for worlds not loaded.
     */
    public void restore() {
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> unresolved = new ArrayList<>();
            int restored = 0;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                String[] parts = line.split("\\t", 5);
                if (parts.length != 5) {
                    unresolved.add(line);
                    continue;
                }
                try {
                    World world = Bukkit.getWorld(UUID.fromString(parts[0]));
                    if (world == null) {
                        unresolved.add(line);
                        continue;
                    }
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    int z = Integer.parseInt(parts[3]);
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) continue; // another actor placed something
                    String data = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                    BlockData original = Bukkit.createBlockData(data);
                    block.setBlockData(original, false);
                    restored++;
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Could not restore journal entry: " + ex.getMessage());
                    unresolved.add(line);
                }
            }
            if (unresolved.isEmpty()) Files.deleteIfExists(file);
            else Files.write(file, unresolved.reversed(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            plugin.getLogger().info("Restored " + restored + " mob-damaged blocks; "
                    + unresolved.size() + " entries require attention.");
        } catch (IOException ex) {
            plugin.getLogger().severe("Mob block journal recovery failed: " + ex.getMessage());
        }
    }
}
