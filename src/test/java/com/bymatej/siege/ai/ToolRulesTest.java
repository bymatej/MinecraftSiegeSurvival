package com.bymatej.siege.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRulesTest {
    @Test void bareHandsBreakDirt() {
        assertTrue(ToolRules.canBreak("AIR", "DIRT"));
        assertTrue(ToolRules.canBreak("AIR", "GRASS_BLOCK"));
        assertTrue(ToolRules.canBreak("AIR", "SAND"));
    }

    @Test void bareHandsBreakWoodAsWell() {
        assertTrue(ToolRules.canBreak("AIR", "OAK_PLANKS"));
        assertTrue(ToolRules.canBreak("AIR", "OAK_LOG"));
        assertTrue(ToolRules.canBreak("AIR", "SPRUCE_WOOD"));
    }

    @Test void cobblestoneNeedsAPickaxe() {
        assertTrue(ToolRules.canBreak("IRON_PICKAXE", "COBBLESTONE"));
        assertTrue(ToolRules.canBreak("DIAMOND_PICKAXE", "COBBLESTONE"));
        assertFalse(ToolRules.canBreak("AIR", "COBBLESTONE"));
        assertFalse(ToolRules.canBreak("IRON_AXE", "COBBLESTONE"));
        assertFalse(ToolRules.canBreak("IRON_SWORD", "STONE"));
    }

    @Test void anyToolBreaksDirtAndWood() {
        assertTrue(ToolRules.canBreak("IRON_SHOVEL", "DIRT"));
        assertTrue(ToolRules.canBreak("IRON_AXE", "OAK_PLANKS"));
        assertTrue(ToolRules.canBreak("IRON_SWORD", "OAK_LOG"));
        assertTrue(ToolRules.canBreak("IRON_PICKAXE", "DIRT"));
    }

    @Test void containersAndValuablesAreNeverBroken() {
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "CHEST"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "BARREL"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "FURNACE"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "REDSTONE_BLOCK"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "DIAMOND_ORE"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "IRON_BLOCK"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "LAVA"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "ANCIENT_DEBRIS"));
    }

    @Test void ironAndCopperDoorsNeedAPickaxe() {
        assertFalse(ToolRules.canBreak("AIR", "IRON_DOOR"));
        assertFalse(ToolRules.canBreak("IRON_AXE", "IRON_DOOR"));
        assertTrue(ToolRules.canBreak("IRON_PICKAXE", "IRON_DOOR"));
        assertFalse(ToolRules.canBreak("IRON_AXE", "COPPER_DOOR"));
    }

    @Test void woodenDoorsAreSimpleBlocks() {
        assertTrue(ToolRules.canBreak("AIR", "OAK_DOOR"));
        assertTrue(ToolRules.canBreak("IRON_AXE", "OAK_TRAPDOOR"));
        assertTrue(ToolRules.canBreak("AIR", "OAK_FENCE_GATE"));
    }

    @Test void laddersStayIntactSoTheMobsCanClimb() {
        assertTrue(ToolRules.isProtected("LADDER"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "LADDER"));
    }

    @Test void unknownBlocksAreNotBreakable() {
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "COMMAND_BLOCK"));
        assertFalse(ToolRules.canBreak("DIAMOND_PICKAXE", "BEDROCK"));
    }

    @Test void aDesperateMobBreaksStoneWithBareHands() {
        // The whole point of the desperation: the mob digs through the wall
        // even without a pickaxe.
        assertFalse(ToolRules.canBreak("AIR", "STONE"));
        assertTrue(ToolRules.canBreakDesperate("AIR", "STONE"));
        assertTrue(ToolRules.canBreakDesperate("IRON_AXE", "COBBLESTONE"));
        assertTrue(ToolRules.canBreakDesperate("AIR", "DEEPSLATE"));
    }

    @Test void aDesperateMobKeepsTheValuableBlocksSafe() {
        // Containers, redstone and ores stay protected - even in desperation.
        assertFalse(ToolRules.canBreakDesperate("AIR", "CHEST"));
        assertFalse(ToolRules.canBreakDesperate("AIR", "BARREL"));
        assertFalse(ToolRules.canBreakDesperate("AIR", "REDSTONE_BLOCK"));
        assertFalse(ToolRules.canBreakDesperate("IRON_PICKAXE", "DIAMOND_ORE"));
        assertFalse(ToolRules.canBreakDesperate("IRON_PICKAXE", "IRON_BLOCK"));
        assertFalse(ToolRules.canBreakDesperate("AIR", "LADDER"));
        assertFalse(ToolRules.canBreakDesperate("AIR", "LAVA"));
    }

    @Test void aDesperateMobStillNeedsAPickaxeForTheRedstoneDoors() {
        assertFalse(ToolRules.canBreakDesperate("AIR", "IRON_DOOR"));
        assertFalse(ToolRules.canBreakDesperate("IRON_AXE", "IRON_DOOR"));
        assertTrue(ToolRules.canBreakDesperate("IRON_PICKAXE", "IRON_DOOR"));
    }
}
