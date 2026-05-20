# PluginHub

PluginHub is an advanced plugin management system for Minecraft Paper/Spigot-based servers. It provides functionality to search, install, update, and remove plugins from a centralized registry, significantly simplifying plugin management for server administrators.

In addition, PluginHub includes powerful access control features (Command Policy Management), world-specific plugin restrictions, and system diagnostics (Doctor), making it an all-in-one server management solution.

---

# 💡 Features

## 1. Plugin Management Commands (`/ph` or `/pluginhub`)

### `/ph install <name> [--platform <p>] [--version <v>] [--minecraftversion <mc>]`
- Installs plugins from the registry.
- Automatically detects the platform (Paper/Spigot/Bukkit/Folia) and Minecraft version.
- Performs dependency checks and displays warnings if necessary.

### `/ph remove <name>` / `/ph delete <name>`
- Removes a plugin.
- `delete` also removes the generated plugin data folder.

### `/ph reinstall <name|all>`
- Reinstalls one or all managed plugins.

### `/ph update <name|all>`
- Updates plugins to the latest available version.

### `/ph list`
- Displays a list of managed plugins.

### `/ph info <name>`
- Displays detailed plugin information.

### `/ph download <url>`
- Downloads a plugin directly from a URL.
- Requires enabling the feature in `config.yml`.

### `/ph doctor`
- Runs system diagnostics.
- Detects missing files, hash mismatches, dependency issues, and more.

### `/ph stats <cpu|tick|task|event>`
- Displays server statistics such as CPU usage, TPS, task history, and event history.

---

# 2. Registry System

## `default-registry.json` (Built-in Plugin Database)
- Stores plugin versions, download URLs, supported platforms, and dependencies.
- Includes popular plugins such as `Essentials` and `Vault` by default.

## External Registry Support
- Custom registry URLs can be configured in `config.yml`.

## Cache System
- Registry information is cached locally for faster access.

---

# 3. Automatic Update System (AutoUpdater)

## Plugin Self-Update
- Automatically downloads the latest version of PluginHub from GitHub during server shutdown.

## Registry Auto-Update
- Automatically downloads the latest `default-registry.json` during server shutdown.

## Configuration

Controlled via:

```yaml
auto-plugin-update.enabled
registry.auto-registry-update.enabled
```

---

# 4. Command Policy Management (`CommandPolicyManager`)

## Role-Based Access Control
- Assign roles to players and manage permissions flexibly.

## World-Specific Command Permissions
- Strictly allow or deny commands on a per-world basis.

## Temporary OP Elevation
- Grants temporary OP privileges only while executing specific commands.

## Related Commands

```bash
/ph role ...
/ph world ...
```

---

# 5. World Plugin Restriction System (`WorldPluginRestrictionManager`)

## Per-World Plugin Restrictions
- Blocks commands from specific plugins in designated worlds.

## Allowed Command List
- Supports whitelist-style exception commands that bypass restrictions.

## Related Commands

```bash
/ph restriction ...
```

---

# ⚙️ Configuration (`config.yml`)

```yaml
download:
  enabled: false  # Enable/disable direct URL downloads

registry:
  url: ""  # External registry URL (empty = built-in registry)
  cache-file: "registry-cache.json"
  connect-timeout-ms: 10000
  read-timeout-ms: 15000
  auto-registry-update:
    enabled: false  # Enable/disable automatic registry updates

doctor:
  dependency-source: repository  # Dependency verification source

confirmations:
  enabled: true  # Enable/disable confirmation prompts for destructive actions

auto-plugin-update:
  enabled: false  # Enable/disable PluginHub self-updating

plugin-restriction:
  enabled: false  # Enable/disable world plugin restrictions

command-policy:
  enabled: false  # Enable/disable command policy management
```

---

# 🛡️ Security Features

- **SHA256 Hash Verification**  
  Verifies downloaded files to prevent tampering or corruption.

- **OP Permission Checks**  
  Administrative commands generally require OP permissions.

- **Confirmation Prompts**  
  Prevents accidental destructive actions such as plugin deletion.

- **Dependency Verification**  
  Warns administrators when required dependencies are missing.

---

# 💡 Usage Examples

```bash
# Install the Essentials plugin
/ph install Essentials

# Install Vault with a specific version and platform
/ph install Vault --version 1.7.3 --platform paper

# Update all managed plugins
/ph update all

# Run system diagnostics
/ph doctor

# Display the list of managed plugins
/ph list

# Remove the Essentials plugin
/ph remove Essentials
```

---

# 💻 Technical Specifications

- **Main Class:** `org2.example.pluginHub.PluginHub`
- **API Version:** `1.21.4`
- **Supported Servers:** Paper, Spigot, Bukkit, Folia
- **Tested Minecraft Versions:** `1.18` - `1.26.1`
- **Java Version:** `21`
- **Build Tool:** Gradle (Kotlin DSL)
- **Dependencies:** Paper API 1.21.4

---

# Class Structure and Responsibilities

| Class Name | Responsibility |
|---|---|
| `PluginHub` | Main plugin class responsible for initialization and management coordination. |
| `PluginHubCommand` | Handles core commands, registry management, and download processing. |
| `AutoUpdater` | Handles automatic updates for PluginHub and registry files from GitHub. |
| `CommandPolicyManager` | Manages role-based command control, world-specific restrictions, and temporary OP elevation. |
| `WorldPluginRestrictionManager` | Manages per-world plugin restrictions and allowed command lists. |
| `PluginHubTabCompleter` | Provides intelligent tab completion for commands. |
| `VersionWrapper` | Handles server and Minecraft version compatibility checks. |

---

# 📝 Summary

PluginHub is a powerful all-in-one management solution that enables server administrators to centrally manage the entire plugin lifecycle — installation, updates, and removal — while also applying advanced security and access policies such as command restrictions and world-specific plugin limitations.

---

# 🤝 Support


If you encounter issues, need help with configuration, or want to suggest features, feel free to join the Discord community.

We can help with:

- Installation help
- Plugin troubleshooting
- Registry creation support
- Feature requests and feedback

You can also open an issue on GitHub for bug reports or reproducible problems.

If you create a high-quality registry, feel free to share it on Discord.  
Exceptional community registries may be included in future official releases.

[![Discord](https://img.shields.io/discord/XXXXX?label=Discord&logo=discord)](https://discord.com/invite/V2UqmDJV68)