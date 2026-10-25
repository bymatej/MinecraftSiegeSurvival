package com.bymatej.siege.command;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.game.MobType;
import com.bymatej.siege.game.MobTypes;
import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class SiegeCommand implements CommandExecutor, TabCompleter, Listener {
    private final SiegePlugin plugin;
    private final SiegeGame game;
    private final Set<Inventory> menus = Collections.newSetFromMap(new IdentityHashMap<>());
    public SiegeCommand(SiegePlugin plugin, SiegeGame game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("/siege setup | anchor | start [waves] | start prep [minutes] [waves]");
            sender.sendMessage("/siege radius <blocks> | status | stop | glow [on|off]");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("status")) { sender.sendMessage(game.status()); return true; }
        if (action.equals("stop") && !(sender instanceof Player)) {
            if (game.running()) game.stop("Match stopped from server console.");
            else sender.sendMessage("No match is active.");
            return true;
        }
        if (action.equals("glow")) {
            // The console may always do this; a player needs the admin permission.
            if (sender instanceof Player player
                    && plugin.getConfig().getBoolean("permissions.require-admin", true)
                    && !player.hasPermission("siege.admin")) {
                sender.sendMessage("You need the siege.admin permission.");
                return true;
            }
            sender.sendMessage(glowAction(args));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can set up or start a siege.");
            return true;
        }
        if (plugin.getConfig().getBoolean("permissions.require-admin", true)
                && !sender.hasPermission("siege.admin")) {
            sender.sendMessage("You need the siege.admin permission.");
            return true;
        }
        switch (action) {
            case "setup" -> showMenu(player);
            case "anchor" -> sender.sendMessage(game.saveAnchor(player));
            case "radius" -> {
                if (args.length < 2) sender.sendMessage("Usage: /siege radius <blocks>");
                else {
                    try { sender.sendMessage(game.setRadius(Integer.parseInt(args[1]))); }
                    catch (NumberFormatException ex) { sender.sendMessage("Radius must be a whole number."); }
                }
            }
            case "start" -> {
                int waves = plugin.getConfig().getInt("game.default-waves", 10);
                int prep = 0;
                try {
                    if (args.length >= 2 && args[1].equalsIgnoreCase("prep")) {
                        prep = args.length >= 3 ? Integer.parseInt(args[2]) :
                                plugin.getConfig().getInt("game.preparation-minutes", 10);
                        if (args.length >= 4) waves = Integer.parseInt(args[3]);
                    } else if (args.length >= 2) waves = Integer.parseInt(args[1]);
                    sender.sendMessage(game.start(player, waves, prep));
                } catch (NumberFormatException ex) { sender.sendMessage("Minutes and waves must be whole numbers."); }
            }
            case "stop" -> {
                if (game.running()) game.stop("Match stopped by " + sender.getName());
                else sender.sendMessage("No match is active.");
            }
            default -> sender.sendMessage("Unknown subcommand. Use /siege help.");
        }
        return true;
    }

    /** The nine roster slots of the enemy menu: three rows of three. */
    private static final int[] ROSTER_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    /**
     * /siege glow [on|off] - without an argument the glow is toggled. The
     * glowing enemies can be found through walls, so a wave that is nearly
     * cleared does not have to run into its timeout.
     */
    private String glowAction(String[] args) {
        GlowArgument argument = GlowArgument.parse(args.length > 1 ? args[1] : null);
        return switch (argument) {
            case TOGGLE -> game.toggleGlow();
            case ON -> game.setGlow(true);
            case OFF -> game.setGlow(false);
            case INVALID -> "Usage: /siege glow [on|off]";
        };
    }

    private void showMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, "Siege: enabled enemies");
        MobType[] types = MobType.values();
        for (int index = 0; index < types.length && index < ROSTER_SLOTS.length; index++) {
            fill(inventory, ROSTER_SLOTS[index], types[index]);
        }
        menus.add(inventory);
        player.openInventory(inventory);
    }

    private void fill(Inventory inventory, int slot, MobType type) {
        ItemStack item = new ItemStack(MobTypes.icon(type));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.display() + (game.allowed(type) ? " [ENABLED]" : " [DISABLED]"));
        List<String> lore = new ArrayList<>();
        lore.add(type.lastWaveOnly() ? "Only in the final wave" : "Appears from wave " + type.minWave());
        if (type.cap() > 0) lore.add("At most " + type.cap() + " per wave");
        meta.setLore(lore);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        menus.remove(event.getInventory());
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!menus.contains(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        MobType type = rosterType(event.getSlot());
        if (type == null) return;
        if (plugin.getConfig().getBoolean("permissions.require-admin", true)
                && !player.hasPermission("siege.admin")) return;
        player.sendMessage(game.toggle(type));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                showMenu(player);
            }
        });
    }

    /** The roster type shown in a menu slot, or null for an empty slot. */
    private static MobType rosterType(int slot) {
        for (int index = 0; index < ROSTER_SLOTS.length; index++) {
            if (ROSTER_SLOTS[index] != slot) continue;
            MobType[] types = MobType.values();
            return index < types.length ? types[index] : null;
        }
        return null;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                  String alias, String[] args) {
        if (args.length == 1) return List.of("help", "setup", "anchor", "start", "radius", "status", "stop", "glow");
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("prep", "10", "5");
        if (args.length == 2 && args[0].equalsIgnoreCase("glow")) return List.of("on", "off");
        return Collections.emptyList();
    }
}
