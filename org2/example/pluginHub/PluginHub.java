package org2.example.pluginHub;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

public class PluginHub extends JavaPlugin {
    private CommandPolicyManager commandPolicyManager;
    private WorldPluginRestrictionManager worldPluginRestrictionManager;
    private AutoUpdater autoUpdater;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String mcVersion = Bukkit.getBukkitVersion().split("-")[0];
        if (!VersionWrapper.isSupportedServerRange(mcVersion)) {
            getLogger().warning("Server version outside tested range (1.18 - 26.1): " + mcVersion);
        }
        // Register commands
        PluginHubCommand commandExecutor = new PluginHubCommand(this);
        getCommand("pluginhub").setExecutor(commandExecutor);
        getCommand("pluginhub").setTabCompleter(new PluginHubTabCompleter(commandExecutor));
        commandPolicyManager = new CommandPolicyManager(this);
        commandPolicyManager.initIfEnabled();
        worldPluginRestrictionManager = new WorldPluginRestrictionManager(this);
        worldPluginRestrictionManager.initIfEnabled();
        autoUpdater = new AutoUpdater(this);
        getLogger().info("PluginHub enabled!");
    }

    @Override
    public void onDisable() {
        if (autoUpdater != null) {
            autoUpdater.updateOnShutdownIfEnabled();
        }
        getLogger().info("PluginHub disabled!");
    }

    public CommandPolicyManager getCommandPolicyManager() {
        return commandPolicyManager;
    }

    public WorldPluginRestrictionManager getWorldPluginRestrictionManager() {
        return worldPluginRestrictionManager;
    }
}
