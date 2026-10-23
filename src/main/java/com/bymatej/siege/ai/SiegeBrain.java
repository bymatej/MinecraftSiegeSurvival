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
    private final BreachPlanner planner;
    private final Map<UUID, Memory> memories = new HashMap<>();

    public SiegeBrain(SiegePlugin plugin, SiegeGame game) {
        this.plugin = plugin;
        this.game = game;
        this.planner = new BreachPlanner(game);
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
            Player target = chooseTarget(mob, memory);
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
            // Even close to the anchor a fortification may obstruct the final steps.

            if (memory.stillTicks >= 4 && plugin.getConfig().getBoolean("ai.door-opening", true)) {
                Block obstacle = forwardBlock(mob, anchor);
                if (obstacle != null && openDoor(obstacle)) {
                    memory.stillTicks = 0;
                    continue;
                }
            }
            if ((memory.stillTicks >= 8 || memory.breakTarget != null)
                    && plugin.getConfig().getBoolean("mining.enabled", false)) {
                // Bound planner work: only a few miners can search each control tick.
                if (miningSlots > 0) {
                    // Keep working on the same obstruction rather than running A*
                    // on every half-second block-breaking progress update.
                    if (memory.breakTarget != null
                            && memory.breakTarget.getWorld() == mob.getWorld()
                            && mob.getLocation().distanceSquared(memory.breakTarget.clone().add(.5, .5, .5)) <= 10.0) {
                        Block activeBlock = mob.getWorld().getBlockAt(memory.breakTarget);
                        if (MiningPolicy.mayBreak(mob, activeBlock)
                                && game.insideDestruction(activeBlock.getLocation())) {
                            tryMine(mob, anchor, memory, activeBlock);
                            miningSlots--;
                            continue;
                        }
                        memory.breakTarget = null;
                        memory.breakTicks = 0;
                    }
                    BreachPlanner.Plan plan = planner.find(mob, anchor);
                    if (plan != null && plan.breakThis() != null) {
                        Block targetBlock = plan.breakThis();
                        if (mob.getLocation().distanceSquared(targetBlock.getLocation().add(.5, .5, .5)) <= 10.0) {
                            tryMine(mob, anchor, memory, targetBlock);
                        } else if (plan.standHere().distanceSquared(mob.getLocation()) > 1) {
                            mob.getPathfinder().moveTo(plan.standHere(), 1.0);
                        }
                        miningSlots--;
                    } else if (plan != null && plan.standHere() != null) {
                        mob.getPathfinder().moveTo(plan.standHere(), 1.0);
                        memory.breakTarget = null;
                        memory.breakTicks = 0;
                    }
                }
            } else {
                memory.breakTarget = null;
                memory.breakTicks = 0;
            }
        }
    }

    private Player chooseTarget(Mob mob, Memory memory) {
        Player current = mob.getTarget() instanceof Player player ? player : null;
        double pursuit = plugin.getConfig().getDouble("ai.pursuit-radius", 20);
        if (current != null && game.alive(current.getUniqueId())
                && current.getWorld() == mob.getWorld()
                && current.getLocation().distanceSquared(mob.getLocation()) <= pursuit * pursuit) {
            if (mob.hasLineOfSight(current)) {
                memory.lostTargetTicks = 0;
                return current;
            }
            // A player hiding behind a wall must not indefinitely distract the siege.
            int maxLost = Math.max(1, plugin.getConfig().getInt("ai.lost-target-seconds", 6)) * 2;
            if (++memory.lostTargetTicks < maxLost) return current;
        }
        memory.lostTargetTicks = 0;
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

    private boolean tryMine(Mob mob, Location anchor, Memory memory, Block block) {
        if (block == null || !game.insideDestruction(block.getLocation())) return false;
        if (!MiningPolicy.mayBreak(mob, block)) return false;
        Location target = block.getLocation();
        if (memory.breakTarget == null || !memory.breakTarget.equals(target)) {
            memory.breakTarget = target.clone();
            memory.breakTicks = 0;
            memory.breakBlockData = block.getBlockData().getAsString();
        }
        if (!block.getBlockData().getAsString().equals(memory.breakBlockData)) {
            memory.breakTarget = null;
            memory.breakTicks = 0;
            return false;
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
            if (!regionProtectionDetected() && canBreakEntireStructure(mob, block)) {
                SiegeBlockBreakEvent event = new SiegeBlockBreakEvent(mob, block);
                Bukkit.getPluginManager().callEvent(event);
                if (!event.isCancelled() && game.journalMobBreak(block)) {
                    Block other = matchingDoorHalf(block);
                    if (other == null || game.journalMobBreak(other)) {
                        // Multi-block doors must be journaled and broken as a unit.
                        block.setType(Material.AIR, false);
                        if (other != null) other.setType(Material.AIR, false);
                    }
                }
            }
            memory.breakTarget = null;
            memory.breakTicks = 0;
            memory.stillTicks = 0;
            mob.getPathfinder().moveTo(anchor, 1.0);
        }
        return true;
    }

    private boolean regionProtectionDetected() {
        if (!plugin.getConfig().getBoolean("mining.deny-if-protection-plugin-present", true))
            return false;
        // Never bypass claims by assuming a plugin-issued BlockBreakEvent is a player break.
        for (String name : List.of("WorldGuard", "GriefPrevention", "Lands", "Towny",
                "Residence", "GriefDefender", "ClaimChunk")) {
            if (Bukkit.getPluginManager().getPlugin(name) != null) return true;
        }
        return false;
    }

    private boolean canBreakEntireStructure(Mob mob, Block block) {
        Block other = matchingDoorHalf(block);
        return other == null || (game.insideDestruction(other.getLocation())
                && MiningPolicy.mayBreak(mob, other));
    }

    private Block matchingDoorHalf(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof org.bukkit.block.data.type.Door door)) return null;
        Block other = block.getRelative(door.getHalf() == Bisected.Half.TOP
                ? BlockFace.DOWN : BlockFace.UP);
        if (other.getType() != block.getType()) return null;
        return other;
    }

    private static final class Memory {
        Location last;
        Location breakTarget;
        String breakBlockData;
        int stillTicks;
        int pathTick;
        int breakTicks;
        int lostTargetTicks;
        Memory(Location initial) { last = initial.clone(); }
    }
}
