package com.bymatej.siege.ai;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Conservative whitelist; all other blocks (including containers) are unbreakable.
 * This intentionally excludes redstone mechanisms, fluid blocks, tile entities,
 * ores and metal blocks until protection and rollback integrations exist.
 */
public final class MiningPolicy {
    private static final Set<Material> PICKAXE_BLOCKS = Set.of(
            Material.STONE, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE,
            Material.DEEPSLATE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.BRICKS, Material.TUFF, Material.SANDSTONE, Material.RED_SANDSTONE);
    private MiningPolicy() { }

    public static boolean mayBreak(Mob mob, Block block) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) return false;
        ItemStack main = equipment.getItemInMainHand();
        Material tool = main.getType();
        if (tool.isAir()) return false;
        String name = tool.name();
        Material material = block.getType();
        if (!material.isBlock() || material.getHardness() < 0) return false;
        if (name.endsWith("_PICKAXE")) return PICKAXE_BLOCKS.contains(material);
        if (name.endsWith("_AXE")) {
            String b = material.name();
            return (b.endsWith("_PLANKS") || b.endsWith("_LOG") || b.endsWith("_WOOD")
                    || b.endsWith("_FENCE") || b.endsWith("_FENCE_GATE")
                    || (b.endsWith("_DOOR") && !b.equals("IRON_DOOR"))
                    || b.endsWith("_TRAPDOOR") && !b.equals("IRON_TRAPDOOR"))
                    && !b.contains("COPPER");
        }
        return false;
    }

    /** Estimated ticks for a normally equipped player; no player potion/status effects. */
    public static int estimatedTicks(Block block, ItemStack tool) {
        double hardness = Math.max(0.1, block.getType().getHardness());
        double destroySpeed = Math.max(0.1, block.getBlockData().getDestroySpeed(tool, true));
        boolean correct = block.getBlockData().isPreferredTool(tool);
        return (int) Math.ceil(hardness * (correct ? 30 : 100) / destroySpeed);
    }
}
