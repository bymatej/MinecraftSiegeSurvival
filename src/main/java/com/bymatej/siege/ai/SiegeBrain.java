package com.bymatej.siege.ai;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Anchor-first, bounded AI controller.
 * Vanilla pathfinding remains primary; experimental breaching handles only
 * one-step obstructions, not arbitrary A* through buildings.
 */
public final class SiegeBrain {
    private static final int CONTROL_INTERVAL_TICKS = 10;
    private final SiegePlugin plugin;
    private final SiegeGame game;
    private final Map<UUID, Memory> memories = new HashMap<>();

    public SiegeBrain(SiegePlugin plugin, SiegeGame game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void tick() {
        if (!game.combat()) {
            memories.clear();
            return;
        }
        Location anchor = game.anchor();
        if (anchor == null) return;
        Set<UUID> active = game.mobIds();
        memories.keySet().removeIf(id -> !active.contains(id));
        int miningSlots = Math.max(0, plugin.getConfig().getInt("mining.max-concurrent-miners", 8));
        for (UUID id : active) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) continue;
            if (mob.getWorld() != anchor.getWorld()) continue;
            Memory memory = memories.computeIfAbsent(id, unused -> new Memory(mob.getLocation()));
            Player target = chooseTarget(mob);
            if (target != null) {
                mob.setTarget(target);
                memory.stillTicks = 0;
                memory.breakTarget = null;
                continue;
            }
            if (mob.getTarget() != null) mob.setTarget(null);
            if (!mob.getPathfinder().hasPath() || ++memory.pathTick >= 4) {
                mob.getPathfinder().moveTo(anchor, 1.0);
                memory.pathTick = 0;
            }
            Location current = mob.getLocation();
            if (current.distanceSquared(memory.last) < 0.12) memory.stillTicks++;
            else memory.stillTicks = 0;
            memory.last = current.clone();
            if (current.distanceSquared(anchor) < 10) continue;

            if (memory.stillTicks >= 4 && plugin.getConfig().getBoolean("ai.door-opening", true)) {
                Block obstacle = forwardBlock(mob, anchor);
                if (obstacle != null && openDoor(obstacle)) {
                    memory.stillTicks = 0;
                    continue;
                }
            }
            if (memory.stillTicks >= 8 && miningSlots > 0
                    && plugin.getConfig().getBoolean("mining.enabled", false)
                    && game.insideDestruction(current)) {
                if (tryMine(mob, anchor, memory)) miningSlots--;
            } else {
                memory.breakTarget = null;
                memory.breakTicks = 0;
            }
        }
    }

    private Player chooseTarget(Mob mob) {
        Player current = mob.getTarget() instanceof Player player ? player : null;
        double pursuit = plugin.getConfig().getDouble("ai.pursuit-radius", 20);
        if (current != null && game.alive(current.getUniqueId())
                && current.getWorld() == mob.getWorld()
                && current.getLocation().distanceSquared(mob.getLocation()) <= pursuit * pursuit)
            return current;
        double radius = plugin.getConfig().getDouble("ai.detection-radius", 12);
        Player nearest = null;
        double nearestDistance = radius * radius;
        for (Player player : game.livingOnline()) {
            if (player.getWorld() != mob.getWorld()) continue;
            double distance = mob.getLocation().distanceSquared(player.getLocation());
            if (distance < nearestDistance && mob.hasLineOfSight(player)) {
                nearest = player; nearestDistance = distance;
            }
        }
        return nearest;
    }

    private Block forwardBlock(Mob mob, Location anchor) {
        Location l = mob.getLocation();
        double dx = anchor.getX() - l.getX(), dz = anchor.getZ() - l.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.001) return null;
        int x = (int) Math.floor(l.getX() + dx / length * 1.0);
        int z = (int) Math.floor(l.getZ() + dz / length * 1.0);
        Block bottom = l.getWorld().getBlockAt(x, l.getBlockY(), z);
        Block top = bottom.getRelative(BlockFace.UP);
        if (!bottom.isPassable()) return bottom;
        if (!top.isPassable()) return top;
        return null;
    }

    private boolean openDoor(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Openable openable)) return false;
        // Treat wooden doors, trapdoors and fence gates; never open iron/redstone doors.
        String type = block.getType().name();
        if (type.equals("IRON_DOOR") || type.equals("IRON_TRAPDOOR")
                || type.contains("COPPER")) return false;
        if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP) {
            block = block.getRelative(BlockFace.DOWN);
            data = block.getBlockData();
            if (!(data instanceof Openable other)) return false;
            openable = other;
        }
        if (openable.isOpen()) return false;
        openable.setOpen(true);
        block.setBlockData(data);
        return true;
    }

    private boolean tryMine(Mob mob, Location anchor, Memory memory) {
        Block block = forwardBlock(mob, anchor);
        if (block == null || !game.insideDestruction(block.getLocation())) return false;
        if (!MiningPolicy.mayBreak(mob, block)) return false;
        Location target = block.getLocation();
        if (memory.breakTarget == null || !memory.breakTarget.equals(target)) {
            memory.breakTarget = target.clone();
            memory.breakTicks = 0;
        }
        ItemStack tool = Objects.requireNonNull(mob.getEquipment()).getItemInMainHand();
        int requiredTicks = Math.max(CONTROL_INTERVAL_TICKS, MiningPolicy.estimatedTicks(block, tool));
        memory.breakTicks += CONTROL_INTERVAL_TICKS;
        float progress = Math.min(1f, (float) memory.breakTicks / requiredTicks);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() == block.getWorld()
                    && player.getLocation().distanceSquared(target) < 48 * 48)
                player.sendBlockDamage(target, progress, mob);
        }
        if (memory.breakTicks >= requiredTicks) {
            // Revalidate against player modifications and fire our custom veto hook.
            if (!game.insideDestruction(target) || !MiningPolicy.mayBreak(mob, block)) {
                memory.breakTarget = null;
                return true;
            }
            SiegeBlockBreakEvent event = new SiegeBlockBreakEvent(mob, block);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) block.setType(Material.AIR, true);
            memory.breakTarget = null;
            memory.breakTicks = 0;
            memory.stillTicks = 0;
            mob.getPathfinder().moveTo(anchor, 1.0);
        }
        return true;
    }

    private static final class Memory {
        Location last;
        Location breakTarget;
        int stillTicks;
        int pathTick;
        int breakTicks;
        Memory(Location initial) { last = initial.clone(); }
    }
}
