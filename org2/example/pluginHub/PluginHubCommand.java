package org2.example.pluginHub;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class PluginHubCommand implements CommandExecutor {
    private final PluginHub plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, RegistryPlugin> pluginsById = new HashMap<>();
    private final Map<String, String> aliasToId = new HashMap<>();
    private final Map<UUID, PendingAction> pendingActions = new HashMap<>();

    public PluginHubCommand(PluginHub plugin) {
        this.plugin = plugin;
        loadRegistry();
    }


    public Set<String> getAllPluginNamesForTab() {
        Set<String> names = new LinkedHashSet<>();
        for (RegistryPlugin p : pluginsById.values()) {
            names.add(p.id);
            if (p.display != null && !p.display.isBlank()) names.add(p.display);
        }
        return names;
    }

    public Set<String> getMinecraftVersionsForPlugin(String pluginName) {
        Set<String> versions = new LinkedHashSet<>();
        RegistryPlugin rp = pluginsById.get(normalize(pluginName));
        if (rp == null) return versions;
        for (RegistryVersion rv : rp.versions.values()) {
            if (rv.minecraftVersions != null) {
                versions.addAll(rv.minecraftVersions);
            }
        }
        return versions;
    }

    public Set<String> getRoleNamesForTab() {
        CommandPolicyManager cpm = plugin.getCommandPolicyManager();
        if (cpm == null) return new HashSet<>();
        return cpm.getRoleNames();
    }

    public Set<String> getWorldNamesForTab() {
        CommandPolicyManager cpm = plugin.getCommandPolicyManager();
        if (cpm != null) {
            Set<String> names = cpm.getWorldNames();
            if (!names.isEmpty()) return names;
        }
        WorldPluginRestrictionManager wprm = plugin.getWorldPluginRestrictionManager();
        if (wprm == null) return new HashSet<>();
        return wprm.getWorldNames();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§cThis command requires OP permission.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "install" -> handleInstall(sender, args);
            case "download" -> handleDownload(sender, args);
            case "remove", "rm" -> handleRemove(sender, args, false);
            case "delete", "del" -> handleRemove(sender, args, true);
            case "reinstall" -> handleReinstall(sender, args);
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, args);
            case "update" -> handleUpdate(sender, args);
            case "doctor" -> handleDoctor(sender);
            case "stats" -> handleStats(sender, args);
            case "role" -> handleRole(sender, args);
            case "world" -> handleWorld(sender, args);
            case "restriction" -> handleRestriction(sender, args);
            case "confirm" -> handleConfirm(sender);
            case "cancel" -> handleCancel(sender);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleInstall(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph install <name> [--platform <name>] [--version <v>] [--minecraftversion <mc>]");
            return true;
        }
        ParsedInstallArgs parsed = parseInstallArgs(sender, args);
        if (parsed == null) return true;

        RegistryPlugin rp = resolvePlugin(parsed.pluginToken);
        if (rp == null) {
            sender.sendMessage("§cPlugin not found: " + parsed.pluginToken);
            return true;
        }
        String platform = parsed.platform != null ? parsed.platform : detectPlatform();
        String mcVersion = parsed.minecraftVersion != null ? parsed.minecraftVersion : detectMinecraftVersion();

        RegistryVersion rv = resolveVersion(rp, parsed.version, platform, mcVersion);
        if (rv == null) {
            sender.sendMessage("§cNo matching version for " + rp.displayOrId() + " platform=" + platform + " mc=" + mcVersion);
            Set<String> availablePlatforms = new HashSet<>();
            for (RegistryVersion version : rp.versions.values()) {
                availablePlatforms.addAll(version.platforms.keySet());
            }
            if (!availablePlatforms.isEmpty()) {
                sender.sendMessage("§eAvailable platforms: " + String.join(", ", availablePlatforms));
            }
            return true;
        }
        PlatformSource source = rv.platforms.get(normalize(platform));
        if (source == null || source.url == null || source.url.isBlank()) {
            source = rv.platforms.get("*");
        }
        final PlatformSource finalSource = source;
        if (finalSource == null || finalSource.url == null || finalSource.url.isBlank()) {
            sender.sendMessage("§cPlatform source not found in registry: " + platform);
            sender.sendMessage("§eAvailable platforms for this version: " + String.join(", ", rv.platforms.keySet()));
            return true;
        }

        Path jarFile = plugin.getDataFolder().getParentFile().toPath().resolve(finalSource.fileNameOrDefault(rp.id, rv.version));
        if (Files.exists(jarFile)) {
            sender.sendMessage("§eAlready installed. Skipped: " + jarFile.getFileName());
            return true;
        }

        List<String> lines = new ArrayList<>();
        lines.add("This will install:");
        lines.add("- plugins/" + jarFile.getFileName());
        List<String> missing = findMissingDependencies(rv.dependencies);
        if (!missing.isEmpty()) {
            lines.add("Missing dependencies (warning):");
            for (String m : missing) lines.add("- " + m);
        }
        List<String> missingRecommended = findMissingDependencies(rv.recommended);
        if (!missingRecommended.isEmpty()) {
            lines.add("Recommended plugins (optional):");
            for (String m : missingRecommended) lines.add("- " + m);
        }
        Runnable action = () -> downloadAndRegister(finalSource, jarFile, rp.id, rv.version, platform, mcVersion, rv.generatedFolders, sender);
        if (isConfirmationEnabled()) {
            lines.add("Continue? [/ph confirm / /ph cancel]");
            pendingActions.put(senderId(sender), PendingAction.of(action));
        } else {
            action.run();
        }
        for (String line : lines) sender.sendMessage("§e" + line);
        return true;
    }

    private boolean handleDownload(CommandSender sender, String[] args) {
        if (!plugin.getConfig().getBoolean("download.enabled", false)) {
            sender.sendMessage("§c/PluginHub download is disabled by config.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph download <url>");
            return true;
        }
        Path out = plugin.getDataFolder().getParentFile().toPath().resolve(getFileNameFromUrl(args[1]));
        downloadRaw(args[1], out, sender, "download", null);
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args, boolean includeFolders) {
        if (args.length < 2) {
            sender.sendMessage(includeFolders ? "§cUsage: /ph delete <name>" : "§cUsage: /ph remove <name>");
            return true;
        }
        ManagedState state = loadManagedState();
        String searchKey = normalize(args[1]);
        ManagedPlugin mp = null;
        
        // First try to find by exact ID match in managed state
        mp = state.plugins.get(searchKey);
        
        // If not found, try to find by file name
        if (mp == null) {
            for (ManagedPlugin candidate : state.plugins.values()) {
                if (normalize(candidate.fileName).equals(searchKey) || 
                    normalize(candidate.id).contains(searchKey) ||
                    (candidate.fileName != null && candidate.fileName.toLowerCase().contains(args[1].toLowerCase()))) {
                    mp = candidate;
                    break;
                }
            }
        }
        
        if (mp == null) {
            sender.sendMessage("§cManaged plugin not found: " + args[1]);
            sender.sendMessage("§eUse /ph list to see managed plugins.");
            return true;
        }

        final ManagedPlugin finalMp = mp;
        List<Path> targets = new ArrayList<>();
        targets.add(plugin.getDataFolder().getParentFile().toPath().resolve(finalMp.fileName));
        if (includeFolders && finalMp.generatedFolders != null) {
            for (String folder : finalMp.generatedFolders) targets.add(plugin.getDataFolder().getParentFile().toPath().resolve(folder));
        }
        sender.sendMessage("§eThis will permanently delete:");
        for (Path p : targets) sender.sendMessage("§e- plugins/" + plugin.getDataFolder().getParentFile().toPath().relativize(p).toString().replace('\\', '/'));
        Runnable action = () -> {
            for (Path p : targets) deleteRecursively(p);
            state.plugins.remove(normalize(finalMp.id));
            saveManagedState(state);
            sender.sendMessage("§aRemoved: " + finalMp.id);
            sendRestartPrompt();
        };
        if (isConfirmationEnabled()) {
            sender.sendMessage("§eContinue? [/ph confirm / /ph cancel]");
            pendingActions.put(senderId(sender), PendingAction.of(action));
        } else {
            action.run();
        }
        return true;
    }

    private boolean handleReinstall(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph reinstall <name|all>");
            return true;
        }
        ManagedState state = loadManagedState();
        List<ManagedPlugin> targets = new ArrayList<>();
        if (args[1].equalsIgnoreCase("all")) targets.addAll(state.plugins.values());
        else {
            String id = resolveIdToken(args[1]);
            if (id != null && state.plugins.containsKey(normalize(id))) targets.add(state.plugins.get(normalize(id)));
        }
        if (targets.isEmpty()) {
            sender.sendMessage("§cNo managed plugin to reinstall.");
            return true;
        }

        for (ManagedPlugin mp : targets) {
            RegistryPlugin rp = pluginsById.get(normalize(mp.id));
            if (rp == null) {
                sender.sendMessage("§eSkipped " + mp.id + ": no registry data.");
                continue;
            }
            RegistryVersion rv = rp.versions.get(mp.version);
            if (rv == null) {
                sender.sendMessage("§eSkipped " + mp.id + ": version missing in registry.");
                continue;
            }
            PlatformSource ps = rv.platforms.get(normalize(mp.platform));
            if (ps == null) {
                ps = rv.platforms.get("*");
            }
            if (ps == null) {
                sender.sendMessage("§eSkipped " + mp.id + ": platform missing in registry.");
                continue;
            }
            Path jar = plugin.getDataFolder().getParentFile().toPath().resolve(mp.fileName);
            downloadAndRegister(ps, jar, mp.id, mp.version, mp.platform, mp.minecraftVersion, mp.generatedFolders, sender);
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        ManagedState state = loadManagedState();
        sender.sendMessage("§eManaged plugins:");
        if (state.plugins.isEmpty()) {
            sender.sendMessage("§7(none)");
            return true;
        }
        for (ManagedPlugin mp : state.plugins.values()) {
            sender.sendMessage("§7- " + mp.id + " " + mp.version + " [" + mp.platform + "] mc=" + mp.minecraftVersion);
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph info <name>");
            return true;
        }
        RegistryPlugin rp = resolvePlugin(args[1]);
        if (rp == null) {
            sender.sendMessage("§cPlugin not found: " + args[1]);
            return true;
        }
        sender.sendMessage("§eID: §f" + rp.id);
        sender.sendMessage("§eDisplay: §f" + rp.displayOrId());
        sender.sendMessage("§eLatest: §f" + rp.latest);
        sender.sendMessage("§eVersions: §f" + String.join(", ", rp.versions.keySet()));
        return true;
    }

    private boolean handleUpdate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph update <name|all>");
            return true;
        }
        ManagedState state = loadManagedState();
        List<UpdatePlan> plans = new ArrayList<>();
        Collection<ManagedPlugin> targets;
        if (args[1].equalsIgnoreCase("all")) targets = state.plugins.values();
        else {
            String id = resolveIdToken(args[1]);
            ManagedPlugin mp = id == null ? null : state.plugins.get(normalize(id));
            targets = mp == null ? List.of() : List.of(mp);
        }
        for (ManagedPlugin mp : targets) {
            RegistryPlugin rp = pluginsById.get(normalize(mp.id));
            if (rp == null) continue;
            RegistryVersion latest = resolveVersion(rp, null, mp.platform, mp.minecraftVersion);
            if (latest != null && !latest.version.equals(mp.version)) {
                PlatformSource ps = latest.platforms.get(normalize(mp.platform));
                if (ps == null) {
                    ps = latest.platforms.get("*");
                }
                if (ps != null) plans.add(new UpdatePlan(mp, latest, ps));
            }
        }
        if (plans.isEmpty()) {
            sender.sendMessage("§eNo updates available.");
            return true;
        }
        sender.sendMessage("§eThis action cannot be undone.");
        for (UpdatePlan p : plans) sender.sendMessage("§e- " + p.current.id + p.current.version + " -> " + p.current.id + p.target.version);
        Runnable action = () -> {
            for (UpdatePlan p : plans) {
                Path target = plugin.getDataFolder().getParentFile().toPath().resolve(p.source.fileNameOrDefault(p.current.id, p.target.version));
                downloadAndRegister(p.source, target, p.current.id, p.target.version, p.current.platform, p.current.minecraftVersion, p.current.generatedFolders, sender);
            }
        };
        if (isConfirmationEnabled()) {
            sender.sendMessage("§eContinue? [/ph confirm / /ph cancel]");
            pendingActions.put(senderId(sender), PendingAction.of(action));
        } else {
            action.run();
        }
        return true;
    }

    private boolean handleDoctor(CommandSender sender) {
        ManagedState state = loadManagedState();
        List<String> issues = new ArrayList<>();
        boolean pluginMode = plugin.getConfig().getString("doctor.dependency-source", "repository").equalsIgnoreCase("plugin");
        Set<String> seen = new HashSet<>();
        for (ManagedPlugin mp : state.plugins.values()) {
            String key = normalize(mp.id);
            if (seen.contains(key)) issues.add("duplicate plugin: " + mp.id);
            seen.add(key);

            Path file = plugin.getDataFolder().getParentFile().toPath().resolve(mp.fileName);
            if (!Files.exists(file)) issues.add("missing file: " + mp.fileName);

            RegistryPlugin rp = pluginsById.get(normalize(mp.id));
            if (rp == null) {
                issues.add("unknown managed state: " + mp.id);
                continue;
            }
            RegistryVersion rv = rp.versions.get(mp.version);
            if (rv == null) {
                issues.add("unknown managed state: " + mp.id + "@" + mp.version);
                continue;
            }
            if (!rv.platforms.containsKey(normalize(mp.platform))) issues.add("unsupported platform: " + mp.id + " " + mp.platform);
            if (!isMcSupported(rv.minecraftVersions, mp.minecraftVersion)) issues.add("unsupported mc version: " + mp.id + " " + mp.minecraftVersion);
            List<String> missingDeps = pluginMode
                    ? findMissingDependenciesFromPlugin(mp.id)
                    : findMissingDependencies(rv.dependencies);
            for (String dep : missingDeps) issues.add("missing dependency: " + mp.id + " -> " + dep);

            PlatformSource ps = rv.platforms.get(normalize(mp.platform));
            if (ps != null && ps.sha256 != null && Files.exists(file)) {
                try {
                    String hash = sha256(file);
                    if (!ps.sha256.equalsIgnoreCase(hash)) issues.add("hash mismatch: " + mp.fileName);
                } catch (Exception e) {
                    issues.add("hash mismatch: " + mp.fileName);
                }
            }
        }
        if (issues.isEmpty()) {
            sender.sendMessage("§aDoctor: no issues found.");
            return true;
        }
        sender.sendMessage("§eDoctor report:");
        for (String issue : issues) sender.sendMessage("§c- " + issue);
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ph stats <cpu|tick|task|event>");
            return true;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (mode.equals("cpu")) {
            try {
                OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                double cpu = os.getProcessCpuLoad() * 100.0;
                sender.sendMessage("§ePluginHub stats (CPU):");
                sender.sendMessage(String.format(Locale.ROOT, "§7- Process CPU: %.2f%%", cpu));
            } catch (Exception e) {
                sender.sendMessage("§cCPU stats unavailable: " + e.getMessage());
            }
            return true;
        }
        if (mode.equals("tick")) {
            try {
                double[] tps = Bukkit.getServer().getTPS();
                sender.sendMessage("§eLast 5s tick snapshot:");
                sender.sendMessage(String.format(Locale.ROOT, "§7- TPS(1m): %.2f TPS(5m): %.2f TPS(15m): %.2f", tps[0], tps[1], tps[2]));
            } catch (Exception e) {
                sender.sendMessage("§cTick stats unavailable: " + e.getMessage());
            }
            return true;
        }
        if (mode.equals("task")) {
            Map<String, Integer> counts = new TreeMap<>();
            Bukkit.getScheduler().getPendingTasks().forEach(task -> {
                String owner = task.getOwner().getName();
                counts.put(owner, counts.getOrDefault(owner, 0) + 1);
            });
            sender.sendMessage("§ePending scheduler tasks by plugin:");
            if (counts.isEmpty()) sender.sendMessage("§7(none)");
            else counts.forEach((k, v) -> sender.sendMessage("§7- " + k + ": " + v + " tasks"));
            return true;
        }
        if (mode.equals("event")) {
            sender.sendMessage("§eRegistered listener snapshot:");
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                int listeners = org.bukkit.event.HandlerList.getRegisteredListeners(p).size();
                if (listeners > 0) sender.sendMessage("§7- " + p.getName() + ": " + listeners + " listeners");
            }
            return true;
        }
        sender.sendMessage("§cUnknown stats mode: " + args[1]);
        return true;
    }



    private boolean handleConfirm(CommandSender sender) {
        PendingAction action = pendingActions.remove(senderId(sender));
        if (action == null) {
            sender.sendMessage("§cNo pending action.");
            return true;
        }
        action.runnable.run();
        return true;
    }

    private boolean handleCancel(CommandSender sender) {
        if (pendingActions.remove(senderId(sender)) == null) {
            sender.sendMessage("§cNo pending action.");
            return true;
        }
        sender.sendMessage("§eCancelled.");
        return true;
    }

    private ParsedInstallArgs parseInstallArgs(CommandSender sender, String[] args) {
        ParsedInstallArgs parsed = new ParsedInstallArgs();
        parsed.pluginToken = args[1];
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            if (!"--version".equalsIgnoreCase(flag) && !"--platform".equalsIgnoreCase(flag) && !"--minecraftversion".equalsIgnoreCase(flag)) {
                sender.sendMessage("§cUnknown option: " + flag);
                return null;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                sender.sendMessage("§cOption requires value: " + flag);
                return null;
            }
            String value = args[++i];
            if ("--version".equalsIgnoreCase(flag)) parsed.version = value;
            if ("--platform".equalsIgnoreCase(flag)) parsed.platform = value;
            if ("--minecraftversion".equalsIgnoreCase(flag)) parsed.minecraftVersion = value;
        }
        return parsed;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§ePluginHub commands:");
        sender.sendMessage("§7/ph install <name> [--platform p] [--version v] [--minecraftversion mc]");
        sender.sendMessage("§7/ph remove|rm <name>");
        sender.sendMessage("§7/ph delete|del <name>");
        sender.sendMessage("§7/ph info <name>");
        sender.sendMessage("§7/ph list");
        sender.sendMessage("§7/ph update <name|all>");
        sender.sendMessage("§7/ph doctor");
        sender.sendMessage("§7/ph stats <cpu|tick|task|event>");
        sender.sendMessage("§7/ph role <add|delete|set|remove|list|cmd> ...");
        sender.sendMessage("§7/ph world <add|delete|list|cmd> ...");
        sender.sendMessage("§7/ph restriction <list|add|delete|plugin|cmd> ...");
        sender.sendMessage("§7/ph reinstall <name|all>");
        sender.sendMessage("§7/ph confirm | /ph cancel");
        sender.sendMessage("§7/ph download <url> (if enabled)");
    }

    private boolean handleRole(CommandSender sender, String[] args) {
        if (plugin.getCommandPolicyManager() == null) {
            sender.sendMessage("§cCommand policy manager is not enabled.");
            return true;
        }
        return plugin.getCommandPolicyManager().handleRoleCommand(sender, args);
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        if (plugin.getCommandPolicyManager() == null) {
            sender.sendMessage("§cCommand policy manager is not enabled.");
            return true;
        }
        return plugin.getCommandPolicyManager().handleWorldCommand(sender, args);
    }

    private boolean handleRestriction(CommandSender sender, String[] args) {
        if (plugin.getWorldPluginRestrictionManager() == null) {
            sender.sendMessage("§cPlugin restriction manager is not enabled.");
            return true;
        }
        return plugin.getWorldPluginRestrictionManager().handleRestrictionCommand(sender, args);
    }

    private void downloadAndRegister(PlatformSource source, Path targetJar, String id, String version, String platform, String mcVersion, List<String> generatedFolders, CommandSender sender) {
        downloadRaw(source.url, targetJar, sender, id + "@" + version, source.sha256);
        ManagedState state = loadManagedState();
        ManagedPlugin mp = new ManagedPlugin();
        mp.id = id;
        mp.version = version;
        mp.platform = platform;
        mp.minecraftVersion = mcVersion;
        mp.fileName = targetJar.getFileName().toString();
        mp.generatedFolders = generatedFolders == null ? new ArrayList<>() : new ArrayList<>(generatedFolders);
        state.plugins.put(normalize(id), mp);
        saveManagedState(state);
        sendRestartPrompt();
    }

    private void sendRestartPrompt() {
        String message = "§b[PluginHub] §6Plugin changes detected. Please restart the server for changes to take effect.";
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(message));
    }

    private void downloadRaw(String urlString, Path outputFile, CommandSender sender, String actionLabel, String expectedSha256) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(plugin.getConfig().getInt("registry.connect-timeout-ms", 10000));
                connection.setReadTimeout(plugin.getConfig().getInt("registry.read-timeout-ms", 15000));
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    final int finalCode = code;
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("§cFailed (" + actionLabel + ") HTTP " + finalCode));
                    return;
                }
                Files.createDirectories(outputFile.getParent());
                try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }
                if (expectedSha256 != null && !expectedSha256.isBlank()) {
                    String actual = sha256(outputFile);
                    if (!expectedSha256.equalsIgnoreCase(actual)) {
                        Files.deleteIfExists(outputFile);
                        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("§cHash mismatch. Download aborted."));
                        return;
                    }
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("§aComplete: " + outputFile.getFileName()));
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("§cError: " + e.getMessage()));
            }
        });
    }

    private RegistryPlugin resolvePlugin(String token) {
        String id = resolveIdToken(token);
        return id == null ? null : pluginsById.get(normalize(id));
    }

    private String resolveIdToken(String token) {
        String n = normalize(token);
        if (pluginsById.containsKey(n)) return n;
        return aliasToId.get(n);
    }

    private RegistryVersion resolveVersion(RegistryPlugin pluginEntry, String requestedVersion, String platform, String mcVersion) {
        if (requestedVersion != null) {
            RegistryVersion rv = pluginEntry.versions.get(requestedVersion);
            if (rv == null) return null;
            if (!rv.platforms.containsKey(normalize(platform)) && !rv.platforms.containsKey("*")) return null;
            if (!isMcSupported(rv.minecraftVersions, mcVersion)) return null;
            rv.version = requestedVersion;
            return rv;
        }
        if (pluginEntry.latest != null) {
            RegistryVersion latest = pluginEntry.versions.get(pluginEntry.latest);
            if (latest != null && (latest.platforms.containsKey(normalize(platform)) || latest.platforms.containsKey("*")) && isMcSupported(latest.minecraftVersions, mcVersion)) {
                latest.version = pluginEntry.latest;
                return latest;
            }
        }
        List<String> versions = new ArrayList<>(pluginEntry.versions.keySet());
        versions.sort(this::compareVersion);
        Collections.reverse(versions);
        for (String v : versions) {
            RegistryVersion rv = pluginEntry.versions.get(v);
            if ((rv.platforms.containsKey(normalize(platform)) || rv.platforms.containsKey("*")) && isMcSupported(rv.minecraftVersions, mcVersion)) {
                rv.version = v;
                return rv;
            }
        }
        return null;
    }

    private boolean isMcSupported(List<String> supported, String mcVersion) {
        if (supported == null || supported.isEmpty()) return true;
        for (String rule : supported) {
            if (rule.equalsIgnoreCase(mcVersion)) return true;
            // メジャー.マイナーのみの指定（例: "1.21"）の場合、1.21系すべてにマッチ
            if (rule.split("\\.").length == 2) {
                String mcMajorMinor = getMajorMinor(mcVersion);
                if (mcMajorMinor != null && mcMajorMinor.equals(rule)) return true;
            }
        }
        return false;
    }

    private String getMajorMinor(String version) {
        int firstDot = version.indexOf('.');
        if (firstDot == -1) return null;
        int secondDot = version.indexOf('.', firstDot + 1);
        if (secondDot == -1) return version;
        return version.substring(0, secondDot);
    }

    private List<String> findMissingDependencies(List<DependencyRequirement> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) return List.of();
        Set<String> installed = new HashSet<>();
        for (Plugin bp : Bukkit.getPluginManager().getPlugins()) installed.add(normalize(bp.getName()));
        List<String> missing = new ArrayList<>();
        for (DependencyRequirement d : dependencies) {
            if (d.name == null) continue;
            if (!installed.contains(normalize(d.name))) missing.add(d.version == null ? d.name : d.name + " " + d.version);
        }
        return missing;
    }

    private List<String> findMissingDependenciesFromPlugin(String pluginIdOrName) {
        Plugin target = null;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (normalize(p.getName()).equals(normalize(pluginIdOrName))) {
                target = p;
                break;
            }
        }
        if (target == null) return List.of("plugin-not-loaded");

        Set<String> installed = new HashSet<>();
        for (Plugin bp : Bukkit.getPluginManager().getPlugins()) installed.add(normalize(bp.getName()));
        List<String> missing = new ArrayList<>();
        List<String> depends = target.getDescription().getDepend();
        List<String> softDepends = target.getDescription().getSoftDepend();
        for (String dep : depends) {
            if (!installed.contains(normalize(dep))) missing.add(dep);
        }
        for (String dep : softDepends) {
            if (!installed.contains(normalize(dep))) missing.add(dep + " (soft)");
        }
        return missing;
    }

    private String detectPlatform() {
        String name = Bukkit.getServer().getName().toLowerCase(Locale.ROOT);
        String version = Bukkit.getVersion().toLowerCase(Locale.ROOT);
        if (name.contains("folia") || version.contains("folia")) return "folia";
        if (name.contains("paper") || version.contains("paper")) return "paper";
        if (name.contains("spigot") || version.contains("spigot")) return "spigot";
        if (name.contains("bukkit") || version.contains("bukkit")) return "bukkit";
        return name;
    }

    private String detectMinecraftVersion() {
        String bukkitVersion = Bukkit.getBukkitVersion();
        int idx = bukkitVersion.indexOf('-');
        return idx > 0 ? bukkitVersion.substring(0, idx) : bukkitVersion;
    }

    private void loadRegistry() {
        pluginsById.clear();
        aliasToId.clear();
        String registryUrl = plugin.getConfig().getString("registry.url", "").trim();
        Path cachePath = plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("registry.cache-file", "registry-cache.json"));
        String json = null;
        if (!registryUrl.isEmpty()) {
            try {
                json = fetchText(registryUrl);
                Files.createDirectories(cachePath.getParent());
                Files.writeString(cachePath, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed URL registry load: " + e.getMessage());
            }
        }
        if (json == null && Files.exists(cachePath)) {
            try {
                json = Files.readString(cachePath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed cache registry load: " + e.getMessage());
            }
        }
        if (json == null) {
            try (InputStream in = plugin.getResource("default-registry.json")) {
                if (in != null) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Files.createDirectories(cachePath.getParent());
                    Files.writeString(cachePath, json, StandardCharsets.UTF_8);
                    plugin.getLogger().info("Registry loaded from default registry and cached.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed default registry load: " + e.getMessage());
            }
        }
        if (json == null) return;

        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonObject() && root.getAsJsonObject().has("plugins")) {
            JsonArray arr = root.getAsJsonObject().getAsJsonArray("plugins");
            for (JsonElement e : arr) parsePluginObject(e.getAsJsonObject());
        } else if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                JsonObject obj = e.getValue().getAsJsonObject();
                if (!obj.has("id")) obj.addProperty("id", e.getKey());
                parsePluginObject(obj);
            }
        }
    }

    private void parsePluginObject(JsonObject obj) {
        RegistryPlugin rp = new RegistryPlugin();
        rp.id = normalize(getAsStringOrNull(obj, "id"));
        if (rp.id == null) return;
        rp.display = getAsStringOrNull(obj, "display");
        rp.latest = getAsStringOrNull(obj, "latest");
        rp.versions = new HashMap<>();

        JsonObject versionsObj = obj.getAsJsonObject("versions");
        if (versionsObj != null) {
            for (Map.Entry<String, JsonElement> ve : versionsObj.entrySet()) {
                String versionKey = ve.getKey();
                JsonObject vObj = ve.getValue().getAsJsonObject();
                RegistryVersion rv = new RegistryVersion();
                rv.version = versionKey;

                JsonObject platformsObj = vObj.getAsJsonObject("platforms");
                if (platformsObj == null || platformsObj.entrySet().isEmpty()) {
                    String directUrl = getAsStringOrNull(vObj, "url");
                    if (directUrl != null && !directUrl.isBlank()) {
                        PlatformSource ps = new PlatformSource();
                        ps.url = directUrl;
                        rv.platforms.put("*", ps);
                    } else {
                        rv.platforms.putAll(parseLegacyPlatformMap(vObj));
                    }
                } else {
                    for (Map.Entry<String, JsonElement> pe : platformsObj.entrySet()) {
                        rv.platforms.put(normalize(pe.getKey()), gson.fromJson(pe.getValue(), PlatformSource.class));
                    }
                }
                if (rv.platforms.isEmpty()) {
                    plugin.getLogger().warning("Skipped invalid version (no platform source): " + rp.id + "@" + rv.version);
                    continue;
                }

                JsonArray mcArray = vObj.getAsJsonArray("minecraftVersions");
                if (mcArray != null) {
                    for (JsonElement me : mcArray) rv.minecraftVersions.add(me.getAsString());
                }

                JsonArray folderArray = vObj.getAsJsonArray("generatedFolders");
                if (folderArray != null) {
                    for (JsonElement fe : folderArray) rv.generatedFolders.add(fe.getAsString());
                }

                JsonElement depElement = vObj.get("dependencies");
                if (depElement != null && depElement.isJsonArray()) {
                    for (JsonElement de : depElement.getAsJsonArray()) {
                        if (de.isJsonPrimitive()) {
                            DependencyRequirement dr = new DependencyRequirement();
                            dr.name = de.getAsString();
                            rv.dependencies.add(dr);
                        } else if (de.isJsonObject()) {
                            DependencyRequirement dr = new DependencyRequirement();
                            JsonObject dojb = de.getAsJsonObject();
                            dr.name = getAsStringOrNull(dojb, "name");
                            dr.version = getAsStringOrNull(dojb, "version");
                            if (dr.name != null && !dr.name.isBlank()) rv.dependencies.add(dr);
                        }
                    }
                }

                JsonElement recElement = vObj.get("recommended");
                if (recElement != null && recElement.isJsonArray()) {
                    for (JsonElement re : recElement.getAsJsonArray()) {
                        if (re.isJsonPrimitive()) {
                            DependencyRequirement dr = new DependencyRequirement();
                            dr.name = re.getAsString();
                            rv.recommended.add(dr);
                        } else if (re.isJsonObject()) {
                            DependencyRequirement dr = new DependencyRequirement();
                            JsonObject robj = re.getAsJsonObject();
                            dr.name = getAsStringOrNull(robj, "name");
                            dr.version = getAsStringOrNull(robj, "version");
                            if (dr.name != null && !dr.name.isBlank()) rv.recommended.add(dr);
                        }
                    }
                }

                rp.versions.put(versionKey, rv);
            }
        }
        pluginsById.put(rp.id, rp);
        aliasToId.put(rp.id, rp.id);
        if (rp.display != null) aliasToId.put(normalize(rp.display), rp.id);
    }

    private String getAsStringOrNull(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.getAsString();
    }

    private Map<String, PlatformSource> parseLegacyPlatformMap(JsonObject vObj) {
        Map<String, PlatformSource> out = new HashMap<>();
        Set<String> metaKeys = Set.of("dependencies", "recommended", "minecraftVersions", "generatedFolders", "source", "url");
        for (Map.Entry<String, JsonElement> entry : vObj.entrySet()) {
            if (metaKeys.contains(entry.getKey())) continue;
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject candidate = entry.getValue().getAsJsonObject();
            String url = getAsStringOrNull(candidate, "url");
            if (url == null || url.isBlank()) continue;
            PlatformSource ps = gson.fromJson(candidate, PlatformSource.class);
            out.put(normalize(entry.getKey()), ps);
        }
        JsonObject sourceObj = vObj.getAsJsonObject("source");
        if (out.isEmpty() && sourceObj != null) {
            String url = getAsStringOrNull(sourceObj, "url");
            if (url != null && !url.isBlank()) {
                PlatformSource ps = gson.fromJson(sourceObj, PlatformSource.class);
                out.put("*", ps);
            }
        }
        return out;
    }

    private String fetchText(String urlString) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(plugin.getConfig().getInt("registry.connect-timeout-ms", 10000));
        c.setReadTimeout(plugin.getConfig().getInt("registry.read-timeout-ms", 15000));
        if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ManagedState loadManagedState() {
        Path file = plugin.getDataFolder().toPath().resolve("managed-state.json");
        if (!Files.exists(file)) return new ManagedState();
        try {
            ManagedState s = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), ManagedState.class);
            if (s == null || s.plugins == null) return new ManagedState();
            return s;
        } catch (Exception e) {
            return new ManagedState();
        }
    }

    private void saveManagedState(ManagedState state) {
        Path file = plugin.getDataFolder().toPath().resolve("managed-state.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }









    private void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) return;
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String getFileNameFromUrl(String urlString) {
        try {
            String path = new URL(urlString).getPath();
            String f = path.substring(path.lastIndexOf('/') + 1);
            return f.isBlank() ? "downloaded_file.jar" : f;
        } catch (Exception e) {
            return "downloaded_file.jar";
        }
    }

    private int compareVersion(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int ai = i < aa.length ? parseIntSafe(aa[i]) : 0;
            int bi = i < bb.length ? parseIntSafe(bb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return a.compareTo(b);
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isConfirmationEnabled() {
        return plugin.getConfig().getBoolean("confirmations.enabled", true);
    }

    private UUID senderId(CommandSender sender) {
        try {
            return (UUID) sender.getClass().getMethod("getUniqueId").invoke(sender);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(("console:" + sender.getName()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static class ParsedInstallArgs {
        String pluginToken;
        String version;
        String platform;
        String minecraftVersion;
    }

    static class RegistryPlugin {
        String id;
        String display;
        String latest;
        Map<String, RegistryVersion> versions = new HashMap<>();

        String displayOrId() {
            return display == null || display.isBlank() ? id : display;
        }
    }

    static class RegistryVersion {
        transient String version;
        List<DependencyRequirement> dependencies = new ArrayList<>();
        List<DependencyRequirement> recommended = new ArrayList<>();
        List<String> minecraftVersions = new ArrayList<>();
        List<String> generatedFolders = new ArrayList<>();
        Map<String, PlatformSource> platforms = new HashMap<>();
    }

    static class PlatformSource {
        String type = "url";
        String url;
        String fileName;
        String sha256;

        String fileNameOrDefault(String id, String version) {
            if (fileName != null && !fileName.isBlank()) return fileName;
            return id + "-" + version + ".jar";
        }
    }

    static class DependencyRequirement {
        String name;
        String version;
    }

    static class ManagedState {
        Map<String, ManagedPlugin> plugins = new LinkedHashMap<>();
    }

    static class ManagedPlugin {
        String id;
        String version;
        String platform;
        String minecraftVersion;
        String fileName;
        List<String> generatedFolders = new ArrayList<>();
    }

    static class PendingAction {
        Runnable runnable;

        static PendingAction of(Runnable runnable) {
            PendingAction p = new PendingAction();
            p.runnable = runnable;
            return p;
        }
    }

    static class UpdatePlan {
        ManagedPlugin current;
        RegistryVersion target;
        PlatformSource source;

        UpdatePlan(ManagedPlugin current, RegistryVersion target, PlatformSource source) {
            this.current = current;
            this.target = target;
            this.source = source;
        }
    }

}
