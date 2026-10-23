package com.bymatej.siege.ai;

import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired before the plugin modifies a block. Listeners may deny the modification.
 * This is NOT Bukkit's player BlockBreakEvent, and external region plugins
 * must integrate explicitly. Mining is off by default.
 */
public final class SiegeBlockBreakEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Mob mob;
    private final Block block;
    private boolean cancelled;
    public SiegeBlockBreakEvent(Mob mob, Block block) {
        this.mob = mob;
        this.block = block;
    }
    public Mob mob() { return mob; }
    public Block block() { return block; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
