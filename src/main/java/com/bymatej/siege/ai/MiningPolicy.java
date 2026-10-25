package com.bymatej.siege.ai;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Conservative whitelist; all other blocks (including containers) are
 * unbreakable. The rules follow the player ones: the right tool is fast, the
 * wrong tool still works but takes much longer. Redstone mechanisms, fluid
 * blocks, tile entities, ores and metal blocks stay protected until protection
 * and rollback integrations exist.
 */
public final class MiningPolicy {
    /**
     * The hardest block a desperate mob breaks. Obsidian and the like stay
     * untouched - the siege is not worth the world.
     */
    private static final double MAX_DESPERATE_HARDNESS = 20;

    private MiningPolicy() { }

    /**
     * True when the mob may break the block with what it holds in its main
     * hand. Bare hands break dirt and wood, a pickaxe breaks stone.
     */
    public static boolean mayBreak(Mob mob, Block block) {
        return mayBreak(mob, block, false);
    }

    /**
     * True when the mob may break the block. A desperate mob digs through
     * stone with its bare hands - it just takes much longer. Containers,
     * redstone, ores and metal blocks stay protected either way.
     */
    public static boolean mayBreak(Mob mob, Block block, boolean desperate) {
        EntityEquipment equipment = mob.getEquipment();
        ItemStack main = equipment == null ? null : equipment.getItemInMainHand();
        String tool = main == null ? "AIR" : main.getType().name();
        Material material = block.getType();
        if (!material.isBlock() || material.getHardness() < 0) return false;
        if (!desperate) return ToolRules.canBreak(tool, material.name());
        if (material.getHardness() > MAX_DESPERATE_HARDNESS) return false;
        return ToolRules.canBreakDesperate(tool, material.name());
    }

    /**
     * Doors, trapdoors and fence gates are obstacles a mob can open instead of
     * break, so they are never a mining target.
     */
    public static boolean isDoor(Block block) {
        if (!(block.getBlockData() instanceof Openable)) return false;
        String name = block.getType().name();
        return name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE");
    }

    /**
     * Doors the siege may open. Iron and copper doors are redstone parts and
     * stay closed - only a pickaxe breaks them.
     */
    public static boolean canOpen(Block block) {
        if (!isDoor(block)) return false;
        String name = block.getType().name();
        return !name.equals("IRON_DOOR") && !name.equals("IRON_TRAPDOOR") && !name.contains("COPPER");
    }

    /**
     * True for the simple building blocks a charging ravager may smash. No
     * containers, no tile entities, no redstone and no fluids.
     */
    public static boolean maySmash(Block block) {
        Material material = block.getType();
        if (!material.isBlock() || material.getHardness() < 0) return false;
        if (material.getHardness() > 20) return false;
        String name = material.name();
        if (ToolRules.isProtected(name)) return false;
        return ToolRules.isWood(name) || ToolRules.isSimple(name) || ToolRules.isSoil(name);
    }

    /** Estimated ticks for a normally equipped player; no player potion/status effects. */
    public static int estimatedTicks(Block block, ItemStack tool) {
        return estimatedTicks(block, tool, false);
    }

    /**
     * Estimated ticks for a break. A desperate mob digs with its bare hands:
     * slower than the right tool, but it gets through.
     */
    public static int estimatedTicks(Block block, ItemStack tool, boolean desperate) {
        double hardness = block.getType().getHardness();
        boolean correct = block.getBlockData().isPreferredTool(tool);
        if (!desperate) {
            double destroySpeed = block.getDestroySpeed(tool, true);
            return MiningTimes.ticks(hardness, destroySpeed, correct);
        }
        double destroySpeed = block.getDestroySpeed(tool, false);
        return MiningTimes.desperateTicks(hardness, destroySpeed, correct);
    }
}
