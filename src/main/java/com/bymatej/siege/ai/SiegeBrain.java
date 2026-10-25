package com.bymatej.siege.ai;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.game.SiegeGame;
import com.bymatej.siege.game.WaveMath;
import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Player-first, bounded AI controller.
 *
 * The mobs hunt the players; the anchor is only where they go when no player
 * is around, and where they spawn. A player who hides behind a wall or in a
 * box is found anyway: the mobs break their way in, jump gaps, bridge wider
 * ones and build themselves up when the player towered above them.
 */
public final class SiegeBrain {
    private static final int CONTROL_INTERVAL_TICKS = 10;
    /**
     * How much closer to the goal a mob has to get within one control tick to
     * count as progress. A mob that jitters against a wall still has to make no
     * progress at all, otherwise it never notices that it is stuck.
     */
    private static final double PROGRESS_EPSILON = 0.6;
    /** Control ticks without progress before the mob starts to look for a way in. */
    private static final int STUCK_TICKS = 8;
    /** Ticks a ladder climb may take before the mob gives up and walks on. */
    private static final int CLIMB_TIMEOUT_TICKS = 200;
    /** Ticks a mob may spend building itself up before it gives up. */
    private static final int PILLAR_TIMEOUT_TICKS = 200;
    /** The vertical speed of a normal vanilla jump. */
    private static final double JUMP_VELOCITY = 0.42;
    /** A stronger jump that clears a whole block. */
    private static final double PILLAR_JUMP_VELOCITY = 0.52;
    /** The fastest a mob may travel sideways, so a repeated jump cannot run away. */
    private static final double MAX_JUMP_SPEED = 0.45;
    /** Ticks a jump is kept alive, in case the mob's own AI cancels it. */
    private static final int JUMP_TICKS = 8;
    /** Ticks between two terrain actions, so the mob does not hop in circles. */
    private static final int TERRAIN_COOLDOWN_TICKS = 8;
    /** Ticks between two debug lines of the same mob. */
    private static final int DEBUG_INTERVAL_TICKS = 20;
    /** How far the ground in front of a mob may drop before it counts as a hole. */
    private static final int HOLE_DEPTH = 3;
    /** How far the gap in front of a stuck mob is measured. */
    private static final int GAP_SCAN = 3;
    /** The block the mobs use to bridge a gap and to pillar up. */
    private static final Material BUILD_BLOCK = Material.COBBLESTONE;
    private final SiegePlugin plugin;
    private final SiegeGame game;
    private final BreachPlanner planner;
    private final Map<UUID, Memory> memories = new HashMap<>();
    /** The claim plugin that already produced a warning, to log it only once. */
    private String warnedProtection;

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
        double pathSpeed = Math.max(0.1, plugin.getConfig().getDouble("ai.path-speed", 1.25));
        for (UUID id : active) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) continue;
            if (mob.getWorld() != anchor.getWorld()) continue;
            Memory memory = memories.computeIfAbsent(id, unused -> new Memory());
            Player target = chooseTarget(mob, memory);
            // The mobs hunt the players. The anchor is the goal only when no
            // player is around - it marks the fortress and the spawn area.
            Location goal = target != null ? target.getLocation() : anchor;
            UUID goalPlayer = target == null ? null : target.getUniqueId();
            double distance = mob.getLocation().distance(goal);
            if (!Objects.equals(memory.goalPlayer, goalPlayer)) {
                // A new goal: the distance to the old one means nothing now.
                memory.goalPlayer = goalPlayer;
                memory.progress.resetGoal(distance);
                memory.breakTarget = null;
                memory.breakTicks = 0;
                memory.breaching = false;
            } else {
                memory.progress.update(distance, PROGRESS_EPSILON);
            }
            if (target != null) {
                mob.setTarget(target);
                // A mob that fights does not climb.
                memory.ladder = null;
                memory.climbPhase = null;
                raiseShield(mob, true, target.getLocation().distanceSquared(mob.getLocation()));
            } else {
                if (mob.getTarget() != null) mob.setTarget(null);
                raiseShield(mob, false, 0);
            }
            if (plugin.getConfig().getBoolean("ai.debug-logging", false)) {
                debug(mob, memory, target, goal, distance);
            }
            // A closed door, a trapdoor or a fence gate in the way is opened.
            // This also runs while the mob climbs a ladder: a trapdoor over the
            // ladder hole would otherwise stop the climb for good, because the
            // mob cannot get its head through it.
            if (plugin.getConfig().getBoolean("ai.door-opening", true) && openNearbyDoor(mob, goal)) {
                memory.progress.reset();
                continue;
            }
            // A mob on a ladder or on a pillar is steered by the fast tick, so
            // the pathfinder must not fight it. The same is true for a jump.
            if (memory.ladder != null || memory.pillar || memory.jumpTicks > 0) {
                memory.progress.reset();
                continue;
            }
            if (target == null && (!mob.getPathfinder().hasPath() || ++memory.pathTick >= 4)) {
                mob.getPathfinder().moveTo(goal, pathSpeed);
                memory.pathTick = 0;
            }
            // A mob that is stuck does not get anywhere on its own: the plugin
            // paths it to the player itself.
            if (target != null && memory.progress.stuckTicks() >= STUCK_TICKS) {
                mob.getPathfinder().moveTo(goal, pathSpeed);
            }
            // Paper's A* does not climb, so a ladder is the way up and the
            // plugin steers the mob up the ladder itself.
            if (plugin.getConfig().getBoolean("ai.ladder-climbing", true)
                    && (memory.progress.stuckTicks() >= STUCK_TICKS || pathBlocked(mob))
                    && startClimb(mob, goal, memory)) {
                memory.progress.reset();
                continue;
            }
            // The ground in front of a stuck mob: a step up, a gap or a bridge.
            if (memory.progress.stuckTicks() >= STUCK_TICKS && handleTerrain(mob, goal, memory)) {
                memory.progress.reset();
                continue;
            }
            if ((memory.breaching || memory.progress.stuckTicks() >= STUCK_TICKS
                    || memory.breakTarget != null)
                    && plugin.getConfig().getBoolean("mining.enabled", false)) {
                // Bound planner work: only a few miners can search each control tick.
                if (miningSlots > 0 && !arrived(mob, goal, target)) {
                    // A long wave makes the mobs desperate: they dig through
                    // stone with their bare hands instead of giving up.
                    boolean desperate = game.desperateMining();
                    // Keep working on the same obstruction rather than running A*
                    // on every half-second block-breaking progress update.
                    if (memory.breakTarget != null
                            && memory.breakTarget.getWorld() == mob.getWorld()
                            && mob.getLocation().distanceSquared(memory.breakTarget.clone().add(.5, .5, .5)) <= 10.0) {
                        Block activeBlock = mob.getWorld().getBlockAt(memory.breakTarget);
                        if (MiningPolicy.mayBreak(mob, activeBlock, desperate)
                                && game.insideDestruction(activeBlock.getLocation())) {
                            tryMine(mob, goal, memory, activeBlock);
                            miningSlots--;
                            continue;
                        }
                        memory.breakTarget = null;
                        memory.breakTicks = 0;
                    }
                    // The search itself is the expensive part: it costs a slot.
                    miningSlots--;
                    BreachPlanner.Plan plan = planner.find(mob, goal, desperate);
                    if (plan != null && plan.breakThis() != null) {
                        Block targetBlock = plan.breakThis();
                        if (mob.getLocation().distanceSquared(targetBlock.getLocation().add(.5, .5, .5)) <= 10.0) {
                            tryMine(mob, goal, memory, targetBlock);
                        } else if (plan.standHere() != null
                                && plan.standHere().distanceSquared(mob.getLocation()) > 1) {
                            mob.getPathfinder().moveTo(plan.standHere(), 1.0);
                        }
                        // The mob is through the wall or into the next one: it
                        // keeps breaking without waiting for another stall.
                        memory.breaching = true;
                    } else if (plan != null && plan.standHere() != null
                            && plan.standHere().distanceSquared(mob.getLocation()) > 1) {
                        // A way around was found: no need to break anything.
                        memory.breaching = false;
                        if (target == null) {
                            // No player to hunt: the planner is the way around the obstacle.
                            mob.getPathfinder().moveTo(plan.standHere(), 1.0);
                        }
                    }
                }
            } else {
                memory.breakTarget = null;
                memory.breakTicks = 0;
                memory.breaching = false;
            }
        }
    }

    /**
     * Steers the mobs that climb a ladder and the mobs that build themselves
     * up. This runs every tick, because a mob needs a steady push to stay on
     * a ladder and to keep jumping.
     */
    public void movementTick() {
        if (!game.combat()) return;
        Location anchor = game.anchor();
        if (anchor == null) return;
        for (Map.Entry<UUID, Memory> entry : memories.entrySet()) {
            Memory memory = entry.getValue();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
                memory.ladder = null;
                memory.climbPhase = null;
                memory.pillar = false;
                continue;
            }
            Location goal = goal(mob, anchor);
            if (memory.ladder != null) {
                driveClimb(mob, goal, memory);
                continue;
            }
            if (memory.pillar) {
                drivePillar(mob, goal, memory);
                continue;
            }
            if (memory.jumpTicks > 0) {
                memory.jumpTicks--;
                if (mob.isOnGround()) {
                    // The mob's own AI cancelled the jump: try again.
                    mob.getPathfinder().stopPathfinding();
                    jump(mob, goal, memory.jumpVertical, memory.jumpHorizontal);
                }
                continue;
            }
            // The ground in front of the mob: a hole has to be jumped or
            // bridged before the mob walks into it. This runs every tick,
            // because a mob walks several blocks between two control ticks.
            handleGround(mob, goal, memory);
        }
    }

    /** The point a mob moves towards: its target player, or the anchor. */
    private Location goal(Mob mob, Location anchor) {
        Player target = mob.getTarget() instanceof Player player ? player : null;
        if (target != null && game.alive(target.getUniqueId())
                && target.getWorld() == mob.getWorld()) {
            return target.getLocation();
        }
        return anchor;
    }

    /**
     * Looks for a ladder that leads from the mob up to the goal and starts the
     * climb. The ladder has to be close to the mob, its lowest block has to
     * be within reach and its top has to come close to the goal level.
     */
    private boolean startClimb(Mob mob, Location anchor, Memory memory) {
        Location position = mob.getLocation();
        int feet = position.getBlockY();
        int anchorY = anchor.getBlockY();
        if (anchorY <= feet + 1) return false; // the goal is not above the mob
        World world = mob.getWorld();
        int mx = position.getBlockX(), mz = position.getBlockZ();
        Block best = null;
        int bestTop = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = mx + dx, z = mz + dz;
                int horizontal = Math.abs(dx) + Math.abs(dz);
                if (horizontal > 2) continue;
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                for (int y = feet - 2; y <= anchorY; y++) {
                    if (world.getBlockAt(x, y, z).getType() != Material.LADDER) continue;
                    if (low == Integer.MAX_VALUE) low = y;
                    high = y;
                }
                if (low == Integer.MAX_VALUE) continue;
                if (low > feet + 1) continue; // the mob cannot reach the run
                if (high < anchorY - 2) continue; // the run does not lead to the goal
                int score = horizontal * 16 + Math.abs(anchorY - high);
                if (score < bestScore) {
                    bestScore = score;
                    best = world.getBlockAt(x, low, z);
                    bestTop = high;
                }
            }
        }
        if (best == null) return false;
        Location column = best.getLocation().add(0.5, 0, 0.5);
        if (game.outside(column)) return false; // a mob never leaves the arena
        memory.ladder = best.getLocation();
        memory.ladderTopY = bestTop;
        memory.climbPhase = LadderClimb.Phase.APPROACH;
        memory.climbTicks = 0;
        memory.climbBestY = feet;
        memory.exit = exitPoint(world, best.getLocation(), bestTop, anchor);
        // The pathfinder would pull the mob off the ladder.
        mob.getPathfinder().stopPathfinding();
        return true;
    }

    /**
     * The platform the mob steps onto when it reaches the top of the ladder:
     * a solid neighbour of the top ladder block with free space above it.
     */
    private Location exitPoint(World world, Location ladder, int topY, Location anchor) {
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block floor = world.getBlockAt(ladder.getBlockX() + face.getModX(), topY,
                    ladder.getBlockZ() + face.getModZ());
            Block above = floor.getRelative(BlockFace.UP);
            if (!floor.getType().isSolid() || !above.isPassable()) continue;
            Location candidate = above.getLocation().add(0.5, 0, 0.5);
            double distance = Math.hypot(candidate.getX() - anchor.getX(), candidate.getZ() - anchor.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // Without a platform the mob steps off towards the goal.
        return best != null ? best : anchor.clone();
    }

    /** Drives one climbing mob: up the ladder and onto the platform at its top. */
    private void driveClimb(Mob mob, Location anchor, Memory memory) {
        Location position = mob.getLocation();
        if (position.getBlockY() > memory.climbBestY) {
            memory.climbBestY = position.getBlockY();
            memory.climbTicks = 0; // the mob is making progress
        }
        memory.climbTicks++;
        if (memory.climbTicks > CLIMB_TIMEOUT_TICKS) {
            memory.ladder = null;
            memory.climbPhase = null;
            return;
        }
        if (memory.climbPhase == null) memory.climbPhase = LadderClimb.Phase.APPROACH;
        Location exit = memory.exit != null ? memory.exit : anchor;
        LadderClimb.Move move = LadderClimb.move(memory.climbPhase,
                position.getX(), position.getY(), position.getZ(),
                memory.ladder.getX() + 0.5, memory.ladder.getZ() + 0.5, memory.ladderTopY,
                exit.getX(), exit.getZ());
        memory.climbPhase = move.phase();
        if (move.phase() == LadderClimb.Phase.DONE) {
            memory.ladder = null;
            memory.climbPhase = null;
            return;
        }
        mob.setVelocity(new Vector(move.vx(), move.vy(), move.vz()));
        mob.lookAt(anchor.getX(), anchor.getY(), anchor.getZ(), LookAnchor.EYES);
    }

    /**
     * Builds the mob up towards a goal that is above it: the mob jumps and
     * puts a block under its own feet, so it reaches a player who towered up.
     */
    private void drivePillar(Mob mob, Location goal, Memory memory) {
        memory.pillarTicks++;
        if (memory.pillarTicks > PILLAR_TIMEOUT_TICKS
                || goal.getY() <= mob.getLocation().getY() + 0.5) {
            memory.pillar = false;
            return;
        }
        if (mob.isOnGround()) {
            memory.pillarBaseY = mob.getLocation().getBlockY();
            // Straight up: the mob has to stay on its own pillar.
            jumpUp(mob, PILLAR_JUMP_VELOCITY);
        } else if (mob.getLocation().getY() >= memory.pillarBaseY + 1.0) {
            // The mob is clear of the block: it becomes the next step.
            if (!placeUnderneath(mob, memory.pillarBaseY)) {
                // The block could not be placed: no pillar, no endless jumping.
                memory.pillar = false;
            }
        }
    }

    /**
     * Gets a stuck mob up to a goal that is above it: the mob builds itself a
     * staircase of blocks. A step in front of the mob is jumped over.
     */
    private boolean handleTerrain(Mob mob, Location goal, Memory memory) {
        if (buildingAllowed() && goal.getY() > mob.getLocation().getY() + 1.5) {
            // The goal is above the mob: it builds itself up.
            memory.pillar = true;
            memory.pillarTicks = 0;
            memory.pillarBaseY = mob.getLocation().getBlockY();
            // The pathfinder would pull the mob off its own pillar.
            mob.getPathfinder().stopPathfinding();
            return true;
        }
        if (!plugin.getConfig().getBoolean("ai.jump-over-gaps", true)) return false;
        Location position = mob.getLocation();
        double dx = goal.getX() - position.getX();
        double dz = goal.getZ() - position.getZ();
        int stepX = Math.abs(dx) > 0.5 ? (dx > 0 ? 1 : -1) : 0;
        int stepZ = Math.abs(dz) > 0.5 ? (dz > 0 ? 1 : -1) : 0;
        if (stepX == 0 && stepZ == 0) return false;
        World world = mob.getWorld();
        int feet = position.getBlockY();
        int fx = position.getBlockX() + stepX;
        int fz = position.getBlockZ() + stepZ;
        // A wall in front of the mob: the miner breaks it.
        if (!world.getBlockAt(fx, feet, fz).isPassable()
                && !world.getBlockAt(fx, feet + 1, fz).isPassable()) return false;
        // A step up in front of the mob: the mob jumps over it.
        if (!world.getBlockAt(fx, feet, fz).isPassable()) {
            startJump(mob, goal, memory, JUMP_VELOCITY, 0.15);
            return true;
        }
        return false;
    }

    /**
     * Looks at the ground in front of a mob: a hole has to be jumped or
     * bridged before the mob walks into it. A trench around the player is no
     * protection - the mob either jumps over it or builds a floor across it.
     */
    private void handleGround(Mob mob, Location goal, Memory memory) {
        if (memory.terrainCooldown > 0) {
            memory.terrainCooldown--;
            return;
        }
        Location position = mob.getLocation();
        // The direction the mob walks: where it moves to, or where its goal is.
        Vector velocity = mob.getVelocity();
        double dx = velocity.getX();
        double dz = velocity.getZ();
        if (dx * dx + dz * dz < 0.01) {
            dx = goal.getX() - position.getX();
            dz = goal.getZ() - position.getZ();
        }
        double length = Math.hypot(dx, dz);
        if (length < 0.001) return;
        int stepX = (int) Math.round(dx / length);
        int stepZ = (int) Math.round(dz / length);
        if (stepX == 0 && stepZ == 0) return;
        // A falling mob is not on any ground: it must not build anything.
        if (!mob.isOnGround()) return;
        World world = mob.getWorld();
        int feet = position.getBlockY();
        int fx = position.getBlockX() + stepX;
        int fz = position.getBlockZ() + stepZ;
        Block front = world.getBlockAt(fx, feet, fz);
        Block above = world.getBlockAt(fx, feet + 1, fz);
        // A wall in front of the mob: the miner breaks it.
        if (!front.isPassable() && !above.isPassable()) return;
        // A step up in front of the mob: the mob jumps over it.
        if (!front.isPassable() && above.isPassable()) {
            if (!plugin.getConfig().getBoolean("ai.jump-over-gaps", true)) return;
            startJump(mob, goal, memory, JUMP_VELOCITY, 0.2);
            memory.terrainAction = "step";
            memory.terrainCooldown = TERRAIN_COOLDOWN_TICKS;
            return;
        }
        int drop = dropDepth(world, fx, fz, feet);
        if (drop == 0) return; // the ground continues on the same level
        int width = gapWidth(world, feet, fx, fz, stepX, stepZ);
        TerrainTools.Action action = TerrainTools.decide(width, drop);
        if (action == TerrainTools.Action.NONE) return;
        // Every attempt costs a cooldown - also a failed one. A mob that may
        // not build must not try it again on every single tick.
        memory.terrainCooldown = TERRAIN_COOLDOWN_TICKS;
        switch (action) {
            case GAP -> {
                if (!plugin.getConfig().getBoolean("ai.jump-over-gaps", true)) return;
                startJump(mob, goal, memory, JUMP_VELOCITY, 0.3);
                memory.terrainAction = "jump " + width + "/" + drop;
            }
            case BRIDGE -> {
                if (!bridgingAllowed()) return;
                if (!buildBridge(mob, goal, world, feet, fx, fz)) return;
                memory.terrainAction = "bridge " + width + "/" + drop;
            }
            default -> { }
        }
    }

    /** How far the ground in front of the mob drops: 0 is the same level. */
    private int dropDepth(World world, int x, int z, int feet) {
        for (int d = 1; d <= HOLE_DEPTH; d++) {
            if (hasFloor(world.getBlockAt(x, feet - d, z))) return d - 1;
        }
        return HOLE_DEPTH;
    }

    /** The number of missing floor cells in front of the mob. */
    private int gapWidth(World world, int feet, int fx, int fz, int stepX, int stepZ) {
        boolean[] floors = new boolean[GAP_SCAN];
        for (int i = 0; i < GAP_SCAN; i++) {
            Block floor = world.getBlockAt(fx + stepX * i, feet - 1, fz + stepZ * i);
            floors[i] = hasFloor(floor);
        }
        return TerrainTools.gapWidth(floors, GAP_SCAN);
    }

    /**
     * True when a block carries the mob: a full block, a stair or a slab all
     * count, water and lava count as well, because the mob swims in them.
     */
    private boolean hasFloor(Block block) {
        if (!block.isPassable()) return true;
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }

    /**
     * Builds a floor over a gap so the mob can walk across it. The block is
     * journaled, so it is removed again when the match ends.
     */
    private boolean buildBridge(Mob mob, Location goal, World world, int feet, int fx, int fz) {
        if (!buildingAllowed()) return false;
        Block block = world.getBlockAt(fx, feet - 1, fz);
        if (!block.getType().isAir()) return false;
        if (!game.insideDestruction(block.getLocation())) return false;
        if (occupiedByPlayer(world, block.getLocation())) return false;
        if (!game.journalMobPlace(block, BUILD_BLOCK)) return false;
        block.setType(BUILD_BLOCK, false);
        return true;
    }

    /** Puts a block under the feet of a mob that is building itself up. */
    private boolean placeUnderneath(Mob mob, int baseY) {
        if (!buildingAllowed()) return false;
        Location position = mob.getLocation();
        Block block = mob.getWorld().getBlockAt(position.getBlockX(), baseY, position.getBlockZ());
        if (!block.getType().isAir()) return false;
        if (!game.insideDestruction(block.getLocation())) return false;
        if (occupiedByPlayer(mob.getWorld(), block.getLocation())) return false;
        if (!game.journalMobPlace(block, BUILD_BLOCK)) return false;
        block.setType(BUILD_BLOCK, false);
        return true;
    }

    /** True when a player stands in the cell, so no block is placed in them. */
    private boolean occupiedByPlayer(World world, Location cell) {
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (at.getWorld() != world) continue;
            if (at.getBlockX() == cell.getBlockX()
                    && at.getBlockY() == cell.getBlockY()
                    && at.getBlockZ() == cell.getBlockZ()) return true;
        }
        return false;
    }

    /**
     * True when the mobs may build themselves up towards a higher goal.
     * Building is a late game ability: it keeps the early waves fair and stops
     * a player from towering up. A wave that runs long unlocks it as well: a
     * player who hides on a roof is reached anyway, after the same time the
     * enemies start glowing.
     */
    private boolean buildingAllowed() {
        if (!plugin.getConfig().getBoolean("ai.building-enabled", true)) return false;
        return AbilityGate.percentOrTime(game.wave(), game.totalWaves(),
                plugin.getConfig().getInt("ai.building-from-wave-percent", 75),
                game.waveSeconds(),
                plugin.getConfig().getInt("ai.building-after-seconds", 300));
    }

    /**
     * True when the mobs may bridge a gap. Bridging works from the first wave:
     * a mob that cannot cross a trench would just stand in front of it.
     */
    private boolean bridgingAllowed() {
        if (!plugin.getConfig().getBoolean("ai.building-enabled", true)) return false;
        return waveGate("ai.bridging-from-wave-percent", 0);
    }

    /** True when the current wave is far enough for a percentage gated ability. */
    private boolean waveGate(String key, int defaultPercent) {
        return AbilityGate.percent(game.wave(), game.totalWaves(),
                plugin.getConfig().getInt(key, defaultPercent));
    }

    /**
     * Jumps the mob towards the goal. Paper has no jump call for mobs, so the
     * velocity does the work. The forward speed of the mob is kept, so the
     * jump covers more ground than a walk - but it never runs away, no matter
     * how often the jump is repeated.
     */
    private void jump(Mob mob, Location goal, double vertical, double horizontal) {
        Location position = mob.getLocation();
        double dx = goal.getX() - position.getX();
        double dz = goal.getZ() - position.getZ();
        double length = Math.hypot(dx, dz);
        Vector velocity = mob.getVelocity();
        double vx = velocity.getX();
        double vz = velocity.getZ();
        if (length > 0.001) {
            vx += dx / length * horizontal;
            vz += dz / length * horizontal;
        }
        double speed = Math.hypot(vx, vz);
        if (speed > MAX_JUMP_SPEED) {
            vx = vx / speed * MAX_JUMP_SPEED;
            vz = vz / speed * MAX_JUMP_SPEED;
        }
        mob.setVelocity(new Vector(vx, vertical, vz));
    }

    /** Jumps straight up, without drifting sideways. */
    private void jumpUp(Mob mob, double vertical) {
        mob.setVelocity(new Vector(0, vertical, 0));
    }

    /**
     * Starts a jump. The jump is kept alive for a few ticks, because the
     * mob's own AI cancels the velocity right away otherwise.
     */
    private void startJump(Mob mob, Location goal, Memory memory, double vertical, double horizontal) {
        memory.jumpTicks = JUMP_TICKS;
        memory.jumpVertical = vertical;
        memory.jumpHorizontal = horizontal;
        // The pathfinder would pull the mob back down.
        mob.getPathfinder().stopPathfinding();
        jump(mob, goal, vertical, horizontal);
    }

    /** True when the current path exists but does not reach its end. */
    private boolean pathBlocked(Mob mob) {
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        return path != null && !path.canReachFinalPoint();
    }

    /**
     * Raises the shield of a mob that is fighting and lowers it again when the
     * fight is over or when the mob is close enough to strike. A mob that
     * holds a shield uses it, it does not just carry it around.
     *
     * @param fighting true while the mob has a target
     * @param distanceSquared the squared distance to that target
     */
    private void raiseShield(Mob mob, boolean fighting, double distanceSquared) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) return;
        boolean raised = mob.hasActiveItem() && mob.getActiveItemHand() == EquipmentSlot.OFF_HAND;
        boolean hasShield = equipment.getItemInOffHand().getType() == Material.SHIELD;
        // The shield goes up while the mob closes in and comes down again when
        // it can hit, so it never blocks its own attack.
        boolean close = fighting && distanceSquared < 9.0;
        boolean want = hasShield && fighting && !close;
        if (want && !raised) {
            mob.startUsingItem(EquipmentSlot.OFF_HAND);
        } else if (!want && raised) {
            mob.clearActiveItem();
        }
    }

    /**
     * Logs what a mob is doing. The log is throttled, so it stays readable
     * when many mobs are on the field.
     */
    private void debug(Mob mob, Memory memory, Player target, Location goal, double distance) {
        if (++memory.debugTicks < DEBUG_INTERVAL_TICKS) return;
        memory.debugTicks = 0;
        plugin.getLogger().info("AI " + mob.getType().name().toLowerCase(Locale.ROOT)
                + " goal=" + (target == null ? "anchor" : target.getName())
                + " dist=" + String.format(Locale.ROOT, "%.1f", distance)
                + " stuck=" + memory.progress.stuckTicks()
                + " mining=" + (memory.breakTarget == null ? "no"
                        : memory.breakTarget.getBlockX() + "," + memory.breakTarget.getBlockY()
                        + "," + memory.breakTarget.getBlockZ())
                + " terrain=" + memory.terrainAction
                + " ladder=" + (memory.ladder != null)
                + " pillar=" + memory.pillar);
    }

    /**
     * Picks the player the mob hunts. The mobs do not need a line of sight:
     * a player who hides in a box has to be found and dug out anyway.
     */
    private Player chooseTarget(Mob mob, Memory memory) {
        double pursuit = plugin.getConfig().getDouble("ai.pursuit-radius", 32);
        double detection = plugin.getConfig().getDouble("ai.detection-radius", 24);
        boolean requireLineOfSight = plugin.getConfig().getBoolean("ai.require-line-of-sight", false);
        Player current = mob.getTarget() instanceof Player player ? player : null;
        String currentId = current != null ? current.getUniqueId().toString() : null;
        Location mobLocation = mob.getLocation();
        double currentDistance = Double.MAX_VALUE;
        if (current != null && current.getWorld() == mob.getWorld()) {
            currentDistance = mobLocation.distanceSquared(current.getLocation());
        }
        Map<String, Player> byId = new LinkedHashMap<>();
        List<TargetPicker.Candidate> candidates = new ArrayList<>();
        for (Player player : game.livingOnline()) {
            String id = player.getUniqueId().toString();
            byId.put(id, player);
            // A player in another world is never a candidate: the distance
            // between two worlds cannot even be measured.
            boolean sameWorld = player.getWorld() == mob.getWorld();
            double distance = sameWorld
                    ? mobLocation.distanceSquared(player.getLocation())
                    : Double.MAX_VALUE;
            candidates.add(new TargetPicker.Candidate(id, distance,
                    sameWorld && mob.hasLineOfSight(player),
                    game.alive(player.getUniqueId()), sameWorld));
        }
        // A player that got out of reach is followed for a moment.
        if (currentId != null && current != null && current.getWorld() == mob.getWorld()
                && currentDistance > pursuit * pursuit) {
            int maxLost = Math.max(1, plugin.getConfig().getInt("ai.lost-target-seconds", 6)) * 2;
            if (++memory.lostTargetTicks < maxLost
                    && (!requireLineOfSight || mob.hasLineOfSight(current))) {
                return current;
            }
        } else {
            memory.lostTargetTicks = 0;
        }
        String chosen = TargetPicker.pick(currentId, currentDistance, candidates,
                detection, pursuit, requireLineOfSight);
        return chosen == null ? null : byId.get(chosen);
    }

    /**
     * True when the mob already stands at its goal and nothing has to be
     * breached. A player behind a wall is not "arrived": the mob has to break
     * in, even when it can already see the player.
     */
    private boolean arrived(Mob mob, Location goal, Player target) {
        double reach = target == null ? 2.5 : 2.0;
        if (mob.getLocation().distance(goal) > reach) return false;
        return target == null || mob.hasLineOfSight(target);
    }

    /**
     * Opens a closed door or gate that stands between the mob and the goal.
     * The search covers the two blocks in front of the mob, the three columns
     * next to them and the four blocks around the mob itself, so a door is
     * found no matter from which side the mob approaches it.
     */
    private boolean openNearbyDoor(Mob mob, Location anchor) {
        Location location = mob.getLocation();
        World world = mob.getWorld();
        int y = location.getBlockY();
        List<int[]> cells = new ArrayList<>();
        double dx = anchor.getX() - location.getX();
        double dz = anchor.getZ() - location.getZ();
        double length = Math.hypot(dx, dz);
        if (length > 0.001) {
            double ux = dx / length, uz = dz / length;
            for (int step = 1; step <= 2; step++) {
                for (int side = -1; side <= 1; side++) {
                    cells.add(new int[]{
                            (int) Math.floor(location.getX() + ux * step - uz * side),
                            (int) Math.floor(location.getZ() + uz * step + ux * side)});
                }
            }
        }
        int bx = location.getBlockX(), bz = location.getBlockZ();
        cells.add(new int[]{bx, bz});
        cells.add(new int[]{bx + 1, bz});
        cells.add(new int[]{bx - 1, bz});
        cells.add(new int[]{bx, bz + 1});
        cells.add(new int[]{bx, bz - 1});
        for (int[] cell : cells) {
            for (int dy = -1; dy <= 2; dy++) {
                Block block = world.getBlockAt(cell[0], y + dy, cell[1]);
                // A block above the head or below the feet is only in reach if
                // it lies flat: a trapdoor in a roof or a floor, a fence gate
                // in a fence. A door stands upright and is always at the level
                // of the mob or right above its head.
                if ((dy < 0 || dy > 1) && !liesFlat(block)) continue;
                // Opening a door changes a block, so it obeys the same radius
                // as the mining: nothing outside the destruction zone is touched.
                if (!game.insideDestruction(block.getLocation())) continue;
                if (MiningPolicy.canOpen(block) && openDoor(block)) return true;
            }
        }
        return false;
    }

    /** True for a block that lies flat: a trapdoor or a fence gate. */
    private boolean liesFlat(Block block) {
        String type = block.getType().name();
        return type.endsWith("_TRAPDOOR") || type.endsWith("_FENCE_GATE");
    }

    /**
     * Opens a door. A door is two blocks tall, so both halves have to be
     * updated - otherwise tall mobs still collide with the upper half.
     */
    private boolean openDoor(Block block) {
        String type = block.getType().name();
        // Treat wooden doors, trapdoors and fence gates; never open iron/redstone doors.
        if (type.equals("IRON_DOOR") || type.equals("IRON_TRAPDOOR")
                || type.contains("COPPER")) return false;
        BlockData data = block.getBlockData();
        if (!(data instanceof Openable openable)) return false;
        // A trapdoor is a single block, even though its data is "bisected": the
        // top half lies flat on the floor, the bottom half hangs from the
        // ceiling. Treating it like a two block door would look for a second
        // half that does not exist and give up.
        if (!type.endsWith("_DOOR")) {
            if (openable.isOpen()) return false;
            openable.setOpen(true);
            block.setBlockData(data, false);
            return true;
        }
        if (data instanceof Bisected bisected) {
            Block bottom = bisected.getHalf() == Bisected.Half.TOP
                    ? block.getRelative(BlockFace.DOWN) : block;
            Block top = bisected.getHalf() == Bisected.Half.TOP
                    ? block : block.getRelative(BlockFace.UP);
            BlockData bottomData = bottom.getBlockData();
            BlockData topData = top.getBlockData();
            if (!(bottomData instanceof Openable bottomOpen)
                    || !(topData instanceof Openable topOpen)) return false;
            if (bottomOpen.isOpen() && topOpen.isOpen()) return false;
            bottomOpen.setOpen(true);
            topOpen.setOpen(true);
            bottom.setBlockData(bottomData, false);
            top.setBlockData(topData, false);
            return true;
        }
        if (openable.isOpen()) return false;
        openable.setOpen(true);
        block.setBlockData(data, false);
        return true;
    }

    private boolean tryMine(Mob mob, Location goal, Memory memory, Block block) {
        if (block == null || !game.insideDestruction(block.getLocation())) return false;
        boolean desperate = game.desperateMining();
        if (!MiningPolicy.mayBreak(mob, block, desperate)) return false;
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
        // The mob is breaking its way in: it keeps doing that until it reaches
        // the player, without waiting for another stall.
        memory.breaching = true;
        ItemStack tool = Objects.requireNonNull(mob.getEquipment()).getItemInMainHand();
        // A block that the mob may not break with its tool takes longer: the
        // desperate break with bare hands is slow, but it gets through.
        double factor = breakTimeFactor();
        if (desperate && !MiningPolicy.mayBreak(mob, block)) {
            factor *= game.desperateBreakFactor();
        }
        int requiredTicks = Math.max(CONTROL_INTERVAL_TICKS,
                (int) Math.ceil(MiningPolicy.estimatedTicks(block, tool, desperate) * factor));
        memory.breakTicks += CONTROL_INTERVAL_TICKS;
        float progress = Math.min(1f, (float) memory.breakTicks / requiredTicks);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() == block.getWorld()
                    && player.getLocation().distanceSquared(target) < 48 * 48)
                player.sendBlockDamage(target, progress, mob);
        }
        if (memory.breakTicks >= requiredTicks) {
            // Revalidate against player modifications and fire our custom veto hook.
            if (!game.insideDestruction(target) || !MiningPolicy.mayBreak(mob, block, desperate)) {
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
            memory.progress.reset();
            mob.getPathfinder().moveTo(goal, 1.0);
        }
        return true;
    }

    /**
     * How long a mob needs to break a block, as a factor of the normal time.
     * The early waves keep the fair, slow breaching; from the last quarter of
     * the waves the mobs break through their walls faster.
     */
    private double breakTimeFactor() {
        double speed = plugin.getConfig().getDouble("mining.late-breach-speed", 2.0);
        return WaveMath.breakTimeFactor(game.wave(), game.totalWaves(), speed);
    }

    private boolean regionProtectionDetected() {
        if (!plugin.getConfig().getBoolean("mining.deny-if-protection-plugin-present", true))
            return false;
        // Never bypass claims by assuming a plugin-issued BlockBreakEvent is a player break.
        String protection = plugin.protectionPlugin();
        if (protection == null) return false;
        if (!protection.equals(warnedProtection)) {
            warnedProtection = protection;
            plugin.getLogger().warning("Siege: " + protection + " is installed, so a siege mob may not "
                    + "break blocks. Set mining.deny-if-protection-plugin-present to false if you "
                    + "accept that siege mobs breach protected blocks.");
        }
        return true;
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
        /** How close the mob has come to its goal so far. */
        final ProgressTracker progress = new ProgressTracker();
        /** The player the mob hunts, null when it goes for the anchor. */
        UUID goalPlayer;
        Location breakTarget;
        String breakBlockData;
        /** True while the mob is breaking its way towards the player. */
        boolean breaching;
        int pathTick;
        int breakTicks;
        int lostTargetTicks;
        /** The ladder the mob climbs, null while it walks. */
        Location ladder;
        /** The Y of the highest ladder block of the run. */
        int ladderTopY;
        /** The platform the mob steps onto at the top of the ladder. */
        Location exit;
        LadderClimb.Phase climbPhase;
        int climbTicks;
        int climbBestY;
        /** True while the mob builds itself up towards a higher goal. */
        boolean pillar;
        /** The block level the mob puts its next step under. */
        int pillarBaseY;
        int pillarTicks;
        /** Ticks a jump is still running. */
        int jumpTicks;
        /** The vertical speed of the running jump. */
        double jumpVertical;
        /** The forward speed of the running jump. */
        double jumpHorizontal;
        /** Ticks until the mob may jump or bridge again. */
        int terrainCooldown;
        /** The last terrain action of the mob, for the debug log. */
        String terrainAction = "none";
        /** Ticks until the next debug line of this mob. */
        int debugTicks;
    }
}
