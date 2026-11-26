# Security Policy

## Supported Versions

**Instigate Hardcore** is in **maintenance mode** (`v1.0.4`). Security fixes and critical stability fixes are targeted at the latest `1.0.x` maintenance release on the supported runtime stack:

| Version | Status | Supported Stack |
| --- | --- | --- |
| `1.0.4` | Supported (maintenance) | Paper `26.3`, Java `25`, PacketEvents `2.14.0` (optional) |
| `< 1.0.4` | Unsupported | Upgrade to `1.0.4` for world-deletion hardening and production-safe defaults |

Other server forks, older Minecraft/Paper versions, or unsupported Java runtimes are not tested or guaranteed.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, pull requests, or discussions.**

Instead, use GitHub's **Private Vulnerability Reporting** on this repository:

1. Open the repository's **Security** tab on GitHub.
2. Select **Advisories** → **Report a vulnerability** (or visit `https://github.com/sukesan7/instigate-hardcore-plugin/security/advisories/new`).
3. Include as much of the following as you can safely share:
   - Affected plugin version, Paper build version, Java version, and whether PacketEvents is installed.
   - A clear description of the vulnerability and its security or data-loss impact (for example: path traversal, unauthorized world deletion, permission bypass, denial of service).
   - Minimal reproduction steps or proof-of-concept configuration.
   - Sanitized server logs (remove private IP addresses, tokens, and unrelated player information).

This is an open-source project in maintenance mode, so response times are best-effort. Maintainers aim to acknowledge private reports promptly, coordinate a fix or mitigation, and publish an advisory once a patched release is available.

## Operational Security & Data-Loss Guidance

### Automatic World Deletion & Backups

> [!WARNING]
> During normal hardcore operation, Instigate Hardcore **automatically creates, unloads, and deletes attempt worlds**. Always maintain regular off-server backups and test configuration changes in a disposable development server first.

Starting in `v1.0.4`, retired world deletion enforces multiple layered safeguards:

- **Canonical containment:** Target folders must resolve canonically inside Paper's configured world container.
- **Symbolic-link rejection:** Symbolic links at the target folder, along any path segment inside the world container, on the ownership marker file, or anywhere inside the directory tree cause deletion to abort before removing files.
- **Legitimate Paper layout validation:** Only managed `instigatehardcore:attempt_<NNNNNN>_(overworld|nether|end)` folders in Paper's namespaced dimension tree (`<level>/dimensions/instigatehardcore/...`) or flat world-container layout are eligible.
- **Ownership markers:** Every managed attempt world records an atomic `.instigatehardcore-owner.properties` file binding plugin name, namespace, world key, world UUID, attempt number, dimension, and seed. Unmarked or mismatched directories are never deleted.
- **Safe existing-installation migration:** Pre-existing worlds from `v1.0.0`–`v1.0.3` receive a migrated ownership marker **only** when they match an existing `(attempt, seed)` already persisted in `world-state.properties` and pass all containment, symlink, and layout checks. Unrelated pre-existing directories are never claimed or deleted.
- **Explicit protection of live worlds:** The configured `worlds.lobby-world`, all vanilla `minecraft:*` dimensions, the plugin data folder, the active world set, the standby world set, persisted active/standby attempts in `world-state.properties`, and any still-loaded Bukkit world are explicitly rejected from deletion.

Even with these safeguards, operators must:

- Keep `worlds.lobby-world` set to a permanent non-attempt world (never point it at an `attempt_*` world).
- Avoid symlinking live server worlds inside the world container.
- Use full server restarts rather than `/reload`.

### Permissions & Production Defaults

- Keep `instigatehardcore.admin` and `instigatehardcore.debug` restricted to trusted server operators (`default: op`).
- Keep `death-replay.debug-preview-enabled: false` on production servers (`false` by default in `v1.0.4`). Only enable it temporarily when operators need `/hcreplaytest` in spectator mode.
- Keep `death-replay.cinematic.resource-pack-hud-enabled: false` unless all players use a matching external resource pack (this feature is **experimental** and no resource pack is bundled).
- Out-of-range numeric values in `config.yml` (such as excessive `worlds.preload-radius-chunks` or invalid `reset.countdown-seconds`) are rejected and safely bounded with a startup warning.

### Supply-Chain Verification

Release builds from this repository enforce:

- Official Gradle wrapper SHA-256 verification (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` plus `gradle/actions/wrapper-validation` in CI).
- Gradle SHA-256 dependency integrity verification (`gradle/verification-metadata.xml`).
- Repository scoping (`exclusiveContent`) so Paper and PacketEvents artifacts resolve only from their authoritative release repositories.
- Minimal read-only GitHub Actions CI (`permissions: contents: read`) with third-party actions pinned to commit SHAs.
- Zero bundled third-party runtime libraries in `InstigateHardcore-<version>.jar` (`paper-api` and `packetevents-spigot` are `compileOnly` server-provided dependencies).
