package com.bymatej.siege.command;

import com.bymatej.siege.SiegePlugin;
import com.bymatej.siege.game.SiegeGame;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
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
            sender.sendMessage("/siege radius <blocks> | status | stop");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("status")) { sender.sendMessage(game.status()); return true; }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can run siege setup/start/stop commands.");
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
                int waves = plugin.getConfig().getInt("game.default-waves", 25);
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

    private void showMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, "Siege: enabled enemies");
        fill(inventory, 11, Material.ROTTEN_FLESH, "Zombies", game.allowed(EntityType.ZOMBIE));
        fill(inventory, 13, Material.BONE, "Skeletons", game.allowed(EntityType.SKELETON));
        fill(inventory, 15, Material.CROSSBOW, "Pillagers", game.allowed(EntityType.PILLAGER));
        menus.add(inventory);
        player.openInventory(inventory);
    }

    private void fill(Inventory inventory, int slot, Material material, String name, boolean enabled) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name + (enabled ? " [ENABLED]" : " [DISABLED]"));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!menus.contains(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        EntityType type = switch (event.getSlot()) {
            case 11 -> EntityType.ZOMBIE;
            case 13 -> EntityType.SKELETON;
            case 15 -> EntityType.PILLAGER;
            default -> null;
        };
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

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                  String alias, String[] args) {
        if (args.length == 1) return List.of("help", "setup", "anchor", "start", "radius", "status", "stop");
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("prep", "25", "10");
        return Collections.emptyList();
    }
}
