package org2.example.pluginHub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WorldPluginRestrictionManager implements Listener {
    private final PluginHub plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private PolicyFile policy = new PolicyFile();
    private Path path;

    public WorldPluginRestrictionManager(PluginHub plugin) {
        this.plugin = plugin;
    }

    public void initIfEnabled() {
        if (!plugin.getConfig().getBoolean("plugin-restriction.enabled", false)) return;
        path = plugin.getDataFolder().toPath().resolve("world-plugin-restrictions.json");
        loadOrCreate();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("plugin-restriction.enabled", false)) return;
        Player p = event.getPlayer();
        WorldRule rule = policy.worlds.get(p.getWorld().getName());
        if (rule == null) return;
        String raw = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String allow : rule.allowCommands) {
            if (lower.startsWith(allow.toLowerCase(Locale.ROOT))) return;
        }
        String cmdLabel = raw.split(" ")[0].toLowerCase(Locale.ROOT);
        String owner = resolveCommandOwner(cmdLabel);
        if (owner != null && containsIgnoreCase(rule.blockedPlugins, owner)) {
            event.setCancelled(true);
            p.sendMessage("§cThis plugin command is disabled in this world: " + owner);
        }
    }

    private void loadOrCreate() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            if (!Files.exists(path)) {
                PolicyFile sample = new PolicyFile();
                WorldRule world = new WorldRule();
                world.blockedPlugins = new ArrayList<>();
                world.allowCommands = new ArrayList<>();
                sample.worlds.put("world", world);
                Files.writeString(path, gson.toJson(sample), StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                PolicyFile loaded = gson.fromJson(reader, PolicyFile.class);
                if (loaded != null && loaded.worlds != null) policy = loaded;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load world-plugin-restrictions.json: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.writeString(path, gson.toJson(policy), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save world-plugin-restrictions.json: " + e.getMessage());
        }
    }

    public Set<String> getWorldNames() {
        return policy.worlds.keySet();
    }

    public boolean handleRestrictionCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph restriction <list|add|delete|plugin|cmd> ...");
            return true;
        }
        if (!sender.isOp() && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            sender.sendMessage("§eWorlds:");
            for (String w : policy.worlds.keySet()) {
                WorldRule rule = policy.worlds.get(w);
                sender.sendMessage("§7- " + w + " blockedPlugins: " + rule.blockedPlugins.size() + " allowCommands: " + rule.allowCommands.size());
            }
            return true;
        }
        if (sub.equals("add") && args.length >= 3) {
            String worldName = args[2];
            if (policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld already exists: " + worldName);
                return true;
            }
            WorldRule newWorld = new WorldRule();
            newWorld.blockedPlugins = new ArrayList<>();
            newWorld.allowCommands = new ArrayList<>();
            policy.worlds.put(worldName, newWorld);
            save();
            sender.sendMessage("§aWorld added: " + worldName);
            return true;
        }
        if (sub.equals("delete") && args.length >= 3) {
            String worldName = args[2];
            if (!policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld not found: " + worldName);
                return true;
            }
            policy.worlds.remove(worldName);
            save();
            sender.sendMessage("§aWorld deleted: " + worldName);
            return true;
        }
        if (sub.equals("plugin") && args.length >= 5) {
            String worldName = args[2];
            if (!policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld not found: " + worldName);
                return true;
            }
            String action = args[3].toLowerCase(Locale.ROOT);
            String pluginName = args[4];
            
            if (!action.equals("add") && !action.equals("remove")) {
                sender.sendMessage("§cUsage: /ph restriction plugin <world> <add|remove> <plugin>");
                return true;
            }
            
            WorldRule world = policy.worlds.get(worldName);
            
            if (action.equals("add")) {
                if (containsIgnoreCase(world.blockedPlugins, pluginName)) {
                    sender.sendMessage("§cPlugin already in blocked list: " + pluginName);
                    return true;
                }
                world.blockedPlugins.add(pluginName);
                save();
                sender.sendMessage("§aPlugin added to blocked list: " + pluginName);
            } else {
                if (!containsIgnoreCase(world.blockedPlugins, pluginName)) {
                    sender.sendMessage("§cPlugin not in blocked list: " + pluginName);
                    return true;
                }
                world.blockedPlugins.removeIf(s -> s.equalsIgnoreCase(pluginName));
                save();
                sender.sendMessage("§aPlugin removed from blocked list: " + pluginName);
            }
            return true;
        }
        if (sub.equals("cmd") && args.length >= 5) {
            String worldName = args[2];
            if (!policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld not found: " + worldName);
                return true;
            }
            String action = args[3].toLowerCase(Locale.ROOT);
            String command = args[4].toLowerCase(Locale.ROOT);
            
            if (!action.equals("add") && !action.equals("remove")) {
                sender.sendMessage("§cUsage: /ph restriction cmd <world> <add|remove> <command>");
                return true;
            }
            
            WorldRule world = policy.worlds.get(worldName);
            
            if (action.equals("add")) {
                if (world.allowCommands.contains(command)) {
                    sender.sendMessage("§cCommand already in allow list: " + command);
                    return true;
                }
                world.allowCommands.add(command);
                save();
                sender.sendMessage("§aCommand added to allow list: " + command);
            } else {
                if (!world.allowCommands.contains(command)) {
                    sender.sendMessage("§cCommand not in allow list: " + command);
                    return true;
                }
                world.allowCommands.remove(command);
                save();
                sender.sendMessage("§aCommand removed from allow list: " + command);
            }
            return true;
        }
        sender.sendMessage("§cUsage: /ph restriction <list|add|delete|plugin|cmd> ...");
        return true;
    }

    private boolean containsIgnoreCase(List<String> list, String target) {
        for (String s : list) if (s.equalsIgnoreCase(target)) return true;
        return false;
    }

    private String resolveCommandOwner(String cmdLabel) {
        try {
            PluginCommand pc = Bukkit.getPluginCommand(cmdLabel);
            if (pc != null && pc.getPlugin() != null) return pc.getPlugin().getName();
            var server = Bukkit.getServer();
            var commandMapField = server.getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            Object cm = commandMapField.get(server);
            if (cm instanceof SimpleCommandMap scm) {
                Command c = scm.getCommand(cmdLabel);
                if (c instanceof PluginIdentifiableCommand pic && pic.getPlugin() != null) {
                    Plugin owner = pic.getPlugin();
                    return owner.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static class PolicyFile {
        Map<String, WorldRule> worlds = new LinkedHashMap<>();
    }

    static class WorldRule {
        List<String> blockedPlugins = new ArrayList<>();
        List<String> allowCommands = new ArrayList<>();
    }
}
