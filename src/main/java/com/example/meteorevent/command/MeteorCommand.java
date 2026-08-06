package com.example.meteorevent.command;

import com.example.meteorevent.MeteorEventPlugin;
import com.example.meteorevent.MeteorManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MeteorCommand implements CommandExecutor, TabCompleter {

    private final MeteorEventPlugin plugin;
    private final MeteorManager manager;

    public MeteorCommand(MeteorEventPlugin plugin, MeteorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(usage());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender);
            case "reload" -> handleReload(sender);
            default -> sender.sendMessage(usage());
        }
        return true;
    }

    private void handleStart(CommandSender sender, String[] args) {
        World world;
        double x;
        double z;

        if (args.length >= 3) {
            try {
                x = Double.parseDouble(args[1]);
                z = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Gecersiz koordinat.", NamedTextColor.RED));
                return;
            }
            world = (sender instanceof Player p) ? p.getWorld() : plugin.getServer().getWorlds().get(0);
        } else if (sender instanceof Player p) {
            Location loc = p.getLocation();
            x = loc.getX();
            z = loc.getZ();
            world = p.getWorld();
        } else {
            sender.sendMessage(Component.text(
                    "Konsoldan calistirirken koordinat vermelisin: /meteor start <x> <z>",
                    NamedTextColor.RED));
            return;
        }

        manager.startMeteorEvent(world, x, z);
        sender.sendMessage(Component.text(
                "Meteor cagirildi: " + world.getName() + " (" + (int) x + ", " + (int) z + ")",
                NamedTextColor.GOLD));
    }

    private void handleStop(CommandSender sender) {
        manager.stopAll();
        sender.sendMessage(Component.text("Tum aktif meteor etkinlikleri iptal edildi.", NamedTextColor.YELLOW));
    }

    private void handleReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(Component.text("config.yml yeniden yuklendi.", NamedTextColor.GREEN));
    }

    private Component usage() {
        return Component.text("Kullanim: /meteor start [x] [z] | stop | reload", NamedTextColor.GRAY);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("start", "stop", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start") && sender instanceof Player p) {
            return List.of(String.valueOf((int) p.getLocation().getX()));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start") && sender instanceof Player p) {
            return List.of(String.valueOf((int) p.getLocation().getZ()));
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix.toLowerCase())) {
                result.add(option);
            }
        }
        return result;
    }
}
