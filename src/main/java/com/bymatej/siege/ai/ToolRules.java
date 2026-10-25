package com.bymatej.siege.ai;

/**
 * The rules for breaking blocks, the way a player would: the right tool is
 * fast, the wrong tool still works but takes much longer. Only block and tool
 * names are handled here, so the rules can be unit tested without a server.
 */
public final class ToolRules {
    private ToolRules() { }

    /**
     * True for blocks that no siege mob may ever break: containers, tile
     * entities, redstone, fluids and everything that is worth more than the
     * siege is worth.
     */
    public static boolean isProtected(String block) {
        return block.contains("CHEST") || block.contains("BARREL") || block.contains("SHULKER")
                || block.contains("FURNACE") || block.contains("BREWING") || block.contains("ANVIL")
                || block.contains("BEACON") || block.contains("ENCHANTING_TABLE")
                || block.contains("LECTERN") || block.contains("COMPOSTER") || block.contains("CAULDRON")
                || block.contains("HOPPER") || block.contains("DROPPER") || block.contains("DISPENSER")
                || block.contains("OBSERVER") || block.contains("PISTON") || block.contains("REDSTONE")
                || block.contains("SPAWNER") || block.contains("BED") || block.contains("PORTAL")
                || block.contains("RESPAWN_ANCHOR") || block.contains("STRUCTURE") || block.contains("CONDUIT")
                || block.contains("JUKEBOX") || block.contains("BELL") || block.contains("CAMPFIRE")
                || block.contains("LANTERN") || block.contains("SOUL_LANTERN") || block.contains("GLOWSTONE")
                || block.contains("LAPIS") || block.contains("DIAMOND") || block.contains("EMERALD")
                || block.contains("GOLD_BLOCK") || block.contains("IRON_BLOCK") || block.contains("NETHERITE_BLOCK")
                || block.contains("COAL_BLOCK") || block.contains("QUARTZ_BLOCK") || block.contains("DEBRIS")
                || block.contains("LAVA") || block.contains("WATER") || block.endsWith("_ORE")
                || block.endsWith("_RAIL") || block.equals("LADDER") || block.endsWith("_LADDER")
                || block.equals("SCAFFOLDING")
                || block.equals("VINE") || block.equals("GLOW_LICHEN") || block.equals("HANGING_ROOTS")
                || block.equals("WEEPING_VINES") || block.equals("TWISTING_VINES");
    }

    /** True for stone and everything else that is mined with a pickaxe. */
    public static boolean isStone(String block) {
        return switch (block) {
            case "STONE", "COBBLESTONE", "COBBLED_DEEPSLATE", "DEEPSLATE", "STONE_BRICKS",
                    "CRACKED_STONE_BRICKS", "MOSSY_STONE_BRICKS", "CHISELED_STONE_BRICKS",
                    "ANDESITE", "POLISHED_ANDESITE", "DIORITE", "POLISHED_DIORITE", "GRANITE",
                    "POLISHED_GRANITE", "BRICKS", "TUFF", "TUFF_BRICKS", "SANDSTONE",
                    "CHISELED_SANDSTONE", "SMOOTH_SANDSTONE", "RED_SANDSTONE", "CHISELED_RED_SANDSTONE",
                    "SMOOTH_RED_SANDSTONE", "BLACKSTONE", "POLISHED_BLACKSTONE", "BASALT",
                    "POLISHED_BASALT", "CALCITE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES",
                    "NETHER_BRICKS", "RED_NETHER_BRICKS", "END_STONE", "END_STONE_BRICKS" -> true;
            default -> false;
        };
    }

    /** True for dirt, sand and the other soft ground blocks. */
    public static boolean isSoil(String block) {
        return switch (block) {
            case "DIRT", "GRASS", "GRASS_BLOCK", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT", "MYCELIUM",
                    "MUD", "MUD_BRICKS", "SAND", "RED_SAND", "GRAVEL", "CLAY", "SOUL_SAND",
                    "SOUL_SOIL", "NETHERRACK", "DIRT_PATH", "FARMLAND" -> true;
            default -> false;
        };
    }

    /** True for wood: planks, logs, wood and leaves. */
    public static boolean isWood(String block) {
        return block.endsWith("_PLANKS") || block.endsWith("_LOG") || block.endsWith("_WOOD")
                || block.endsWith("_LEAVES") || block.equals("BAMBOO_BLOCK") || block.equals("BAMBOO_MOSAIC");
    }

    /** True for the simple building blocks that carry nothing of value. */
    public static boolean isSimple(String block) {
        return block.endsWith("_SLAB") || block.endsWith("_STAIRS") || block.endsWith("_FENCE")
                || block.endsWith("_FENCE_GATE") || block.endsWith("_DOOR") || block.endsWith("_TRAPDOOR")
                || block.endsWith("_WALL") || block.endsWith("_HEDGE") || block.contains("GLASS")
                || block.contains("WOOL") || block.contains("CONCRETE") || block.contains("TERRACOTTA")
                || block.equals("HAY_BLOCK") || block.equals("DRIED_KELP_BLOCK")
                || block.equals("NETHER_WART_BLOCK") || block.equals("CRAFTING_TABLE")
                || block.equals("CARTOGRAPHY_TABLE") || block.equals("SMITHING_TABLE")
                || block.equals("CHISELED_BOOKSHELF") || block.equals("BOOKSHELF");
    }

    /**
     * True when the tool can break the block at all. Bare hands work on
     * everything but stone; stone needs a pickaxe, like in vanilla.
     *
     * @param tool the name of the tool in the main hand, AIR for bare hands
     * @param block the name of the block
     */
    public static boolean canBreak(String tool, String block) {
        if (isProtected(block)) return false;
        // Iron and copper doors are redstone parts: only a pickaxe breaks them.
        if ((block.endsWith("_DOOR") || block.endsWith("_TRAPDOOR"))
                && (block.equals("IRON_DOOR") || block.equals("IRON_TRAPDOOR") || block.contains("COPPER"))) {
            return tool.endsWith("_PICKAXE");
        }
        if (isStone(block)) return tool.endsWith("_PICKAXE");
        if (isSoil(block) || isWood(block) || isSimple(block)) return true;
        return false;
    }

    /**
     * True when a desperate mob may break the block. A mob that has been
     * stuck for a long time digs through stone with its bare hands instead of
     * standing in front of the wall. The blocks that are worth more than the
     * siege stay protected, and the redstone doors still need a pickaxe.
     *
     * @param tool the name of the tool in the main hand, AIR for bare hands
     * @param block the name of the block
     */
    public static boolean canBreakDesperate(String tool, String block) {
        if (isProtected(block)) return false;
        // Iron and copper doors are redstone parts: only a pickaxe breaks them.
        if ((block.endsWith("_DOOR") || block.endsWith("_TRAPDOOR"))
                && (block.equals("IRON_DOOR") || block.equals("IRON_TRAPDOOR") || block.contains("COPPER"))) {
            return tool.endsWith("_PICKAXE");
        }
        return true;
    }
}
