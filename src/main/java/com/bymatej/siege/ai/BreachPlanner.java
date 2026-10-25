package com.bymatej.siege.ai;

import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Bounded, ground-level A* route around simple walls and through tool-compatible
 * openings. Vanilla navigation handles open terrain, terrain elevation and stairs.
 * This fallback is deliberately limited to same-level siege breaches: it cannot
 * excavate tunnels through mountains, bridge voids or solve arbitrary 3D mazes.
 */
public final class BreachPlanner {
    private static final int MAX_STEPS = 16;
    /**
     * The search has to be able to look at the whole area around the mob:
     * with a smaller budget it explores the cheap way around a wall and
     * gives up before it ever tries to break through it.
     */
    private static final int MAX_VISITED = 800;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public record Plan(Location standHere, Block breakThis) { }
    private record Cell(int x, int z) { }
    private record Candidate(Cell cell, double cost, double estimated) { }

    private final SiegeGame game;

    public BreachPlanner(SiegeGame game) { this.game = game; }

    public Plan find(Mob mob, Location anchor) {
        return find(mob, anchor, false);
    }

    /**
     * Plans a route to the goal.
     *
     * @param desperate true when the mob may dig through anything, so stone
     *                  counts as a way out as well
     */
    public Plan find(Mob mob, Location anchor, boolean desperate) {
        if (mob.getWorld() != anchor.getWorld()) return null;
        World world = mob.getWorld();
        int floorY = mob.getLocation().getBlockY();
        Cell start = new Cell(mob.getLocation().getBlockX(), mob.getLocation().getBlockZ());
        int targetX = anchor.getBlockX(), targetZ = anchor.getBlockZ();
        Cell target = new Cell(
                start.x() + Math.max(-MAX_STEPS, Math.min(MAX_STEPS, targetX - start.x())),
                start.z() + Math.max(-MAX_STEPS, Math.min(MAX_STEPS, targetZ - start.z())));
        PriorityQueue<Candidate> open = new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimated));
        Map<Cell, Double> score = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        score.put(start, 0d);
        open.add(new Candidate(start, 0, heuristic(start, target)));
        Cell best = start;
        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            Candidate node = open.poll();
            if (node.cost() > score.getOrDefault(node.cell(), Double.POSITIVE_INFINITY)) continue;
            Cell here = node.cell();
            if (heuristic(here, target) < heuristic(best, target)) best = here;
            if (here.equals(target)) { best = here; break; }
            for (int[] direction : DIRS) {
                Cell next = new Cell(here.x() + direction[0], here.z() + direction[1]);
                if (Math.abs(next.x() - start.x()) > MAX_STEPS
                        || Math.abs(next.z() - start.z()) > MAX_STEPS
                        || !world.isChunkLoaded(next.x() >> 4, next.z() >> 4)) continue;
                double cost = traverseCost(mob, next, floorY, desperate);
                if (!Double.isFinite(cost)) continue;
                double tentative = node.cost() + cost;
                if (tentative >= score.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                score.put(next, tentative);
                previous.put(next, here);
                open.add(new Candidate(next, tentative, tentative + heuristic(next, target)));
            }
        }
        if (best.equals(start)) return null;
        List<Cell> path = new ArrayList<>();
        for (Cell cell = best; cell != null; cell = previous.get(cell)) {
            path.add(cell);
            if (cell.equals(start)) break;
        }
        Collections.reverse(path);
        // Walk to the next reachable segment; stop at the first mined obstruction.
        Cell preceding = start;
        for (int i = 1; i < path.size(); i++) {
            Cell cell = path.get(i);
            Block obstacle = obstruction(world, cell, floorY);
            if (obstacle != null) {
                if (!MiningPolicy.mayBreak(mob, obstacle, desperate)
                        || !game.insideDestruction(obstacle.getLocation())) return null;
                return new Plan(new Location(world, preceding.x() + .5, floorY, preceding.z() + .5), obstacle);
            }
            preceding = cell;
            if (i >= 4) break; // small waypoint to allow vanilla collision/pathfinding to adjust
        }
        return new Plan(new Location(world, preceding.x() + .5, floorY, preceding.z() + .5), null);
    }

    private double traverseCost(Mob mob, Cell cell, int feetY, boolean desperate) {
        World world = mob.getWorld();
        Block floor = world.getBlockAt(cell.x(), feetY - 1, cell.z());
        if (!floor.getType().isSolid() || floor.getType() == Material.MAGMA_BLOCK
                || floor.getType() == Material.CACTUS || floor.getType() == Material.BEDROCK) return Double.POSITIVE_INFINITY;
        Block feet = world.getBlockAt(cell.x(), feetY, cell.z());
        Block head = world.getBlockAt(cell.x(), feetY + 1, cell.z());
        ItemStack tool = Objects.requireNonNull(mob.getEquipment()).getItemInMainHand();
        double cost = 1;
        if (!feet.isPassable()) {
            // A closed door is opened, not broken - even a mob without a tool
            // walks through it.
            if (MiningPolicy.canOpen(feet)) {
                cost += 0.5;
            } else {
                if (!MiningPolicy.mayBreak(mob, feet, desperate) || !game.insideDestruction(feet.getLocation()))
                    return Double.POSITIVE_INFINITY;
                cost += breakCost(mob, feet, tool, desperate);
            }
        }
        if (!head.isPassable()) {
            if (MiningPolicy.canOpen(head)) {
                cost += 0.5;
            } else {
                if (!MiningPolicy.mayBreak(mob, head, desperate) || !game.insideDestruction(head.getLocation()))
                    return Double.POSITIVE_INFINITY;
                cost += breakCost(mob, head, tool, desperate);
            }
        }
        return cost;
    }

    /**
     * The price of breaking a block on the way. The price is capped: the
     * search only picks the place to dig, the digging itself is timed when
     * the mob breaks the block. A high price would make the planner walk
     * around a wall instead of digging through it.
     */
    private double breakCost(Mob mob, Block block, ItemStack tool, boolean desperate) {
        double ticks = MiningPolicy.estimatedTicks(block, tool, desperate);
        return MiningTimes.searchCost(ticks);
    }

    /** The block that has to be mined to continue, or null when the way is open. */
    private static Block obstruction(World world, Cell cell, int y) {
        Block feet = world.getBlockAt(cell.x(), y, cell.z());
        if (!feet.isPassable() && !MiningPolicy.canOpen(feet)) return feet;
        Block head = world.getBlockAt(cell.x(), y + 1, cell.z());
        return head.isPassable() || MiningPolicy.canOpen(head) ? null : head;
    }

    private static double heuristic(Cell a, Cell b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }
}
