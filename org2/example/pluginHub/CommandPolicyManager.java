package org2.example.pluginHub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CommandPolicyManager implements Listener {
    private final PluginHub plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private PolicyFile policy = new PolicyFile();
    private Path path;

    public CommandPolicyManager(PluginHub plugin) {
        this.plugin = plugin;
    }

    public void initIfEnabled() {
        if (!plugin.getConfig().getBoolean("command-policy.enabled", false)) return;
        path = plugin.getDataFolder().toPath().resolve("command-policy.json");
        loadOrCreate();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("command-policy.enabled", false)) return;
        Player player = event.getPlayer();
        String world = player.getWorld().getName();
        String commandLine = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        String commandLabel = commandLine.split(" ")[0].toLowerCase(Locale.ROOT);
        String commandFull = commandLine.toLowerCase(Locale.ROOT);

        ResolvedRole role = resolveRole(player);
        boolean invert = policy.invertDefault;
        WorldCommandRule w = policy.worlds.getOrDefault(world, new WorldCommandRule());

        boolean denied = false;
        boolean allowed = false;
        if (invert) {
            denied = true;
            if (matchAny(role.allow, commandLabel, commandFull)) {
                denied = false;
                allowed = true;
            }
            if (matchAny(w.allow, commandLabel, commandFull)) {
                denied = false;
                allowed = true;
            }
        } else {
            if (matchAny(w.deny, commandLabel, commandFull)) denied = true;
            if (matchAny(role.deny, commandLabel, commandFull)) denied = true;
            if (matchAny(role.allow, commandLabel, commandFull)) {
                denied = false;
                allowed = true;
            }
            if (matchAny(w.allow, commandLabel, commandFull)) {
                denied = false;
                allowed = true;
            }
        }

        if (denied) {
            event.setCancelled(true);
            player.sendMessage("§cThis command is blocked by server policy.");
            return;
        }

        if (allowed && !player.isOp()) {
            boolean wasOp = player.isOp();
            player.setOp(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setOp(wasOp);
            });
        }
    }

    public Set<String> getRoleNames() {
        return policy.roles.keySet();
    }

    public Set<String> getWorldNames() {
        return policy.worlds.keySet();
    }

    public boolean handleWorldCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph world <add|delete|list|cmd> ...");
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
                WorldCommandRule rule = policy.worlds.get(w);
                sender.sendMessage("§7- " + w + " allow: " + rule.allow.size() + " deny: " + rule.deny.size());
            }
            return true;
        }
        if (sub.equals("add") && args.length >= 3) {
            String worldName = args[2];
            if (policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld already exists: " + worldName);
                return true;
            }
            WorldCommandRule newWorld = new WorldCommandRule();
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
        if (sub.equals("cmd") && args.length >= 6) {
            String worldName = args[2];
            if (!policy.worlds.containsKey(worldName)) {
                sender.sendMessage("§cWorld not found: " + worldName);
                return true;
            }
            String type = args[3].toLowerCase(Locale.ROOT);
            String action = args[4].toLowerCase(Locale.ROOT);
            String command = args[5].toLowerCase(Locale.ROOT);
            
            if (!type.equals("allow") && !type.equals("deny")) {
                sender.sendMessage("§cUsage: /ph world cmd <world> <allow|deny> <add|remove> <command>");
                return true;
            }
            if (!action.equals("add") && !action.equals("remove")) {
                sender.sendMessage("§cUsage: /ph world cmd <world> <allow|deny> <add|remove> <command>");
                return true;
            }
            
            WorldCommandRule world = policy.worlds.get(worldName);
            List<String> targetList = type.equals("allow") ? world.allow : world.deny;
            
            if (action.equals("add")) {
                if (targetList.contains(command)) {
                    sender.sendMessage("§cCommand already in " + type + " list: " + command);
                    return true;
                }
                targetList.add(command);
                save();
                sender.sendMessage("§aCommand added to " + type + " list: " + command);
            } else {
                if (!targetList.contains(command)) {
                    sender.sendMessage("§cCommand not in " + type + " list: " + command);
                    return true;
                }
                targetList.remove(command);
                save();
                sender.sendMessage("§aCommand removed from " + type + " list: " + command);
            }
            return true;
        }
        sender.sendMessage("§cUsage: /ph world <add|delete|list|cmd> ...");
        return true;
    }

    public boolean handleRoleCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph role <add|delete|set|remove|list|cmd> ...");
            return true;
        }
        if (!sender.isOp() && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            sender.sendMessage("§eRoles:");
            for (String r : policy.roles.keySet()) sender.sendMessage("§7- " + r);
            sender.sendMessage("§ePlayer assignments:");
            policy.players.forEach((k, v) -> sender.sendMessage("§7- " + k + " => " + v));
            return true;
        }
        if (sub.equals("add") && args.length >= 3) {
            String roleName = args[2];
            if (policy.roles.containsKey(roleName)) {
                sender.sendMessage("§cRole already exists: " + roleName);
                return true;
            }
            RoleRule newRole = new RoleRule();
            policy.roles.put(roleName, newRole);
            save();
            sender.sendMessage("§aRole added: " + roleName);
            return true;
        }
        if (sub.equals("delete") && args.length >= 3) {
            String roleName = args[2];
            if (roleName.equals("default")) {
                sender.sendMessage("§cCannot delete default role.");
                return true;
            }
            if (!policy.roles.containsKey(roleName)) {
                sender.sendMessage("§cRole not found: " + roleName);
                return true;
            }
            policy.roles.remove(roleName);
            policy.players.entrySet().removeIf(entry -> entry.getValue().equals(roleName));
            save();
            sender.sendMessage("§aRole deleted: " + roleName);
            return true;
        }
        if (sub.equals("set") && args.length >= 4) {
            Player p = Bukkit.getPlayerExact(args[2]);
            if (p == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            String role = args[3];
            if (!policy.roles.containsKey(role)) {
                sender.sendMessage("§cRole not found: " + role);
                return true;
            }
            policy.players.put(p.getUniqueId().toString(), role);
            save();
            sender.sendMessage("§aRole set: " + p.getName() + " -> " + role);
            return true;
        }
        if (sub.equals("remove") && args.length >= 3) {
            Player p = Bukkit.getPlayerExact(args[2]);
            if (p == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            policy.players.remove(p.getUniqueId().toString());
            save();
            sender.sendMessage("§aRole removed: " + p.getName());
            return true;
        }
        if (sub.equals("cmd") && args.length >= 6) {
            String roleName = args[2];
            if (!policy.roles.containsKey(roleName)) {
                sender.sendMessage("§cRole not found: " + roleName);
                return true;
            }
            String type = args[3].toLowerCase(Locale.ROOT);
            String action = args[4].toLowerCase(Locale.ROOT);
            String command = args[5].toLowerCase(Locale.ROOT);
            
            if (!type.equals("allow") && !type.equals("deny")) {
                sender.sendMessage("§cUsage: /ph role cmd <role> <allow|deny> <add|remove> <command>");
                return true;
            }
            if (!action.equals("add") && !action.equals("remove")) {
                sender.sendMessage("§cUsage: /ph role cmd <role> <allow|deny> <add|remove> <command>");
                return true;
            }
            
            RoleRule role = policy.roles.get(roleName);
            List<String> targetList = type.equals("allow") ? role.allow : role.deny;
            
            if (action.equals("add")) {
                if (targetList.contains(command)) {
                    sender.sendMessage("§cCommand already in " + type + " list: " + command);
                    return true;
                }
                targetList.add(command);
                save();
                sender.sendMessage("§aCommand added to " + type + " list: " + command);
            } else {
                if (!targetList.contains(command)) {
                    sender.sendMessage("§cCommand not in " + type + " list: " + command);
                    return true;
                }
                targetList.remove(command);
                save();
                sender.sendMessage("§aCommand removed from " + type + " list: " + command);
            }
            return true;
        }
        sender.sendMessage("§cUsage: /ph role <add|delete|set|remove|list|cmd> ...");
        return true;
    }

    private void loadOrCreate() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            if (!Files.exists(path)) {
                PolicyFile sample = new PolicyFile();
                sample.roles.put("default", new RoleRule());
                sample.worlds.put("world", new WorldCommandRule());
                Files.writeString(path, gson.toJson(sample), StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                PolicyFile loaded = gson.fromJson(reader, PolicyFile.class);
                if (loaded != null) policy = loaded;
            }
            if (!policy.roles.containsKey("default")) policy.roles.put("default", new RoleRule());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load command-policy.json: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.writeString(path, gson.toJson(policy), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save command-policy.json: " + e.getMessage());
        }
    }

    private ResolvedRole resolveRole(Player player) {
        String roleName = policy.players.getOrDefault(player.getUniqueId().toString(), "default");
        Set<String> visited = new HashSet<>();
        ResolvedRole out = new ResolvedRole();
        mergeRole(roleName, out, visited);
        return out;
    }

    private void mergeRole(String name, ResolvedRole out, Set<String> visited) {
        if (visited.contains(name)) return;
        visited.add(name);
        RoleRule role = policy.roles.get(name);
        if (role == null) return;
        for (String parent : role.inherits) mergeRole(parent, out, visited);
        out.allow.addAll(role.allow);
        out.deny.addAll(role.deny);
    }

    private boolean matchAny(List<String> rules, String label, String full) {
        for (String r : rules) {
            String v = r.toLowerCase(Locale.ROOT);
            if (v.equals("*")) return true;
            if (label.equals(v)) return true;
            if (full.startsWith(v)) return true;
        }
        return false;
    }

    static class PolicyFile {
        boolean invertDefault = false;
        Map<String, WorldCommandRule> worlds = new LinkedHashMap<>();
        Map<String, RoleRule> roles = new LinkedHashMap<>();
        Map<String, String> players = new LinkedHashMap<>();
    }

    static class WorldCommandRule {
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
    }

    static class RoleRule {
        List<String> inherits = new ArrayList<>(List.of("default"));
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
    }

    static class ResolvedRole {
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
    }
}
