package org2.example.pluginHub;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class PluginHubTabCompleter implements TabCompleter {
    private final PluginHubCommand command;

    public PluginHubTabCompleter(PluginHubCommand command) {
        this.command = command;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.add("install");
            suggestions.add("remove");
            suggestions.add("rm");
            suggestions.add("delete");
            suggestions.add("del");
            suggestions.add("reinstall");
            suggestions.add("info");
            suggestions.add("list");
            suggestions.add("update");
            suggestions.add("doctor");
            suggestions.add("stats");
            suggestions.add("role");
            suggestions.add("world");
            suggestions.add("restriction");
            suggestions.add("confirm");
            suggestions.add("cancel");
            suggestions.add("help");
            suggestions.add("download");
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("install") || sub.equals("remove") || sub.equals("rm") || sub.equals("delete")
                    || sub.equals("del") || sub.equals("info") || sub.equals("reinstall") || sub.equals("update")) {
                suggestions.addAll(command.getAllPluginNamesForTab());
                if (sub.equals("reinstall") || sub.equals("update")) suggestions.add("all");
            } else if (sub.equals("stats")) {
                suggestions.add("cpu");
                suggestions.add("tick");
                suggestions.add("task");
                suggestions.add("event");
            } else if (sub.equals("role")) {
                suggestions.add("add");
                suggestions.add("delete");
                suggestions.add("set");
                suggestions.add("remove");
                suggestions.add("list");
                suggestions.add("cmd");
            } else if (sub.equals("world")) {
                suggestions.add("add");
                suggestions.add("delete");
                suggestions.add("list");
                suggestions.add("cmd");
            } else if (sub.equals("restriction")) {
                suggestions.add("list");
                suggestions.add("add");
                suggestions.add("delete");
                suggestions.add("plugin");
                suggestions.add("cmd");
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("install")) {
                suggestions.add("--version");
                suggestions.add("--platform");
                suggestions.add("--minecraftversion");
            } else if (sub.equals("role")) {
                String roleSub = args[1].toLowerCase(Locale.ROOT);
                if (roleSub.equals("delete")) {
                    suggestions.addAll(command.getRoleNamesForTab());
                } else if (roleSub.equals("cmd")) {
                    suggestions.addAll(command.getRoleNamesForTab());
                }
            } else if (sub.equals("world")) {
                String worldSub = args[1].toLowerCase(Locale.ROOT);
                if (worldSub.equals("delete")) {
                    suggestions.addAll(command.getWorldNamesForTab());
                } else if (worldSub.equals("cmd")) {
                    suggestions.addAll(command.getWorldNamesForTab());
                }
            } else if (sub.equals("restriction")) {
                String restrictionSub = args[1].toLowerCase(Locale.ROOT);
                if (restrictionSub.equals("delete")) {
                    suggestions.addAll(command.getWorldNamesForTab());
                } else if (restrictionSub.equals("plugin")) {
                    suggestions.addAll(command.getWorldNamesForTab());
                } else if (restrictionSub.equals("cmd")) {
                    suggestions.addAll(command.getWorldNamesForTab());
                }
            }
        } else if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("role")) {
                String roleSub = args[1].toLowerCase(Locale.ROOT);
                if (roleSub.equals("cmd")) {
                    suggestions.add("allow");
                    suggestions.add("deny");
                }
            } else if (sub.equals("world")) {
                String worldSub = args[1].toLowerCase(Locale.ROOT);
                if (worldSub.equals("cmd")) {
                    suggestions.add("allow");
                    suggestions.add("deny");
                }
            } else if (sub.equals("restriction")) {
                String restrictionSub = args[1].toLowerCase(Locale.ROOT);
                if (restrictionSub.equals("plugin")) {
                    suggestions.add("add");
                    suggestions.add("remove");
                } else if (restrictionSub.equals("cmd")) {
                    suggestions.add("add");
                    suggestions.add("remove");
                }
            }
        } else if (args.length == 5) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("role")) {
                String roleSub = args[1].toLowerCase(Locale.ROOT);
                if (roleSub.equals("cmd")) {
                    suggestions.add("add");
                    suggestions.add("remove");
                }
            } else if (sub.equals("world")) {
                String worldSub = args[1].toLowerCase(Locale.ROOT);
                if (worldSub.equals("cmd")) {
                    suggestions.add("add");
                    suggestions.add("remove");
                }
            }
        } else if (args.length >= 4 && args[0].equalsIgnoreCase("install")) {
            if ("--version".equalsIgnoreCase(args[args.length - 2])) {
                // version candidates omitted intentionally (registry-resolved dynamically at runtime)
            } else if ("--platform".equalsIgnoreCase(args[args.length - 2])) {
                suggestions.add("paper");
                suggestions.add("spigot");
                suggestions.add("bukkit");
                suggestions.add("folia");
            } else if ("--minecraftversion".equalsIgnoreCase(args[args.length - 2])) {
                if (args.length >= 2) {
                    suggestions.addAll(command.getMinecraftVersionsForPlugin(args[1]));
                }
            } else {
                suggestions.add("--version");
                suggestions.add("--platform");
                suggestions.add("--minecraftversion");
            }
        }

        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().distinct().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(current)).collect(Collectors.toList());
    }
}
