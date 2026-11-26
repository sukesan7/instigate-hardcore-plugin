# Development

## Overview

This document describes the build, supply-chain verification, and testing workflow for **Instigate Hardcore** (`v1.0.5`, maintenance mode).

The project is a Java `25` Paper `26.3` plugin built with Gradle.

```text
edit
  ↓
unit & security tests (./gradlew clean test)
  ↓
verified build (./gradlew clean build)
  ↓
disposable local Paper server (./gradlew runServer)
  ↓
manual regression checklist
  ↓
coherent commit
```

`server/` is disposable local development infrastructure and is excluded from Git (except `server/.gitkeep`). **Never test world-rotation or cleanup changes against production worlds.**

---

## Supported Toolchain

| Component | Version |
| --- | --- |
| Minecraft / Paper | `26.3` (`io.papermc.paper:paper-api:26.3.build.157-beta`) |
| Java toolchain | `25` |
| Gradle Wrapper | `9.8.0` (SHA-256 verified) |
| PacketEvents (optional runtime) | `2.14.0` (`compileOnly`) |
| JUnit | `6.1.2` (Jupiter) |
| Run-Paper Gradle plugin | `3.1.0` |

Gradle compiles and runs tests using the configured Java `25` toolchain even if the host shell runs a newer JDK. Keep the `paper-api` version identical between `compileOnly` and `testImplementation`.

---

## Repository Layout

```text
instigate-hardcore/
├── .github/workflows/ci.yml      # Read-only Java 25 CI with pinned actions
├── .gitignore
├── LICENSE.md                    # MIT License (packaged into JAR META-INF/)
├── README.md
├── SECURITY.md                   # Private vulnerability reporting & safety policy
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── verification-metadata.xml # SHA-256 dependency verification lockfile
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DEVELOPMENT.md
│   ├── ROADMAP.md
│   └── assets/gallery/
├── server/                       # Disposable local Paper dev server (ignored)
└── src/
    ├── main/
    │   ├── java/dev/instigatehardcore/
    │   └── resources/
    │       ├── config.yml
    │       └── plugin.yml
    └── test/
        ├── java/dev/instigatehardcore/
        └── resources/
```

---

## Build & Supply-Chain Integrity

### Standard Commands

```bash
# Run all automated unit and security tests
./gradlew clean test

# Build the release JAR (verifies dependencies and packages META-INF/LICENSE.md)
./gradlew clean build

# Start the disposable local Paper 26.3 server (installs PacketEvents 2.14.0)
./gradlew runServer
```

The release JAR is written to `build/libs/InstigateHardcore-<version>.jar` and contains `META-INF/LICENSE.md`, `plugin.yml`, `config.yml`, and compiled plugin classes only (zero bundled third-party dependencies).

### Dependency & Wrapper Verification

1. **Gradle Wrapper:** `gradle/wrapper/gradle-wrapper.properties` pins `distributionSha256Sum` to the official Gradle `9.8.0` checksum, and CI runs `gradle/actions/wrapper-validation`.
2. **Repository Restrictions:** `build.gradle.kts` uses `exclusiveContent` so Paper (`io.papermc.paper`, `net.md-5`, `com.mojang`) resolves only from PaperMC, PacketEvents (`com.github.retrooper`) resolves only from CodeMC releases (`releasesOnly()`), and everything else resolves from Maven Central (`releasesOnly()`). Snapshot repositories are excluded.
3. **SHA-256 Dependency Verification:** `gradle/verification-metadata.xml` verifies SHA-256 checksums for all build plugins, compile dependencies, and test dependencies. Only regenerate it when intentionally updating a dependency:

```bash
./gradlew --write-verification-metadata sha256 clean test build
```

Always inspect the resulting diff in `gradle/verification-metadata.xml` before committing.

### Dependency Model

- `io.papermc.paper:paper-api` and `com.github.retrooper:packetevents-spigot` must remain `compileOnly` (server-provided at runtime). Do not shade or bundle Paper or PacketEvents into the plugin JAR.
- `testImplementation` includes `paper-api` so JUnit tests can exercise `YamlConfiguration`, `Statistic`, and Bukkit data models without a live server.

---

## Configuration & Plugin Metadata

Shipped defaults live in `src/main/resources/config.yml`, and numeric bounds are enforced centrally in `dev.instigatehardcore.core.ConfigBounds`:

- `death-replay.debug-preview-enabled` defaults to **`false`** for production safety.
- The deprecated `death-replay.cinematic.resource-pack-hud-enabled` key has been removed from shipped `config.yml` and is safely ignored (with a single startup warning if left `true` in a legacy config). Deathcam always renders standard Adventure components.
- Numeric limits (`reset.countdown-seconds: 1..600`, `worlds.preload-radius-chunks: 0..8`, scoreboard/TAB intervals, replay capture/terrain caps, and low-health heart thresholds) warn and fall back to safe defaults rather than failing startup.
- `plugin.yml` defines `/hardcore` (`/hc`) and `/hcreplaytest` along with `instigatehardcore.command` (`true`), `instigatehardcore.admin` (`op`), and `instigatehardcore.debug` (`op`).

Do not add `/reload` support. Full server restarts are required for a plugin managing live worlds, async cleanup, scheduler tasks, telemetry, and recovery state.

---

## Automated Tests

Automated tests under `src/test/java/dev/instigatehardcore/` use JUnit `6.1.2` and `@TempDir` for filesystem isolation (tests must never write to `server/` or live server paths).

Coverage includes:

- **World safety & recovery (`world/`):** Canonical containment, Paper `26.3` nested dimension layout and flat layout deletion, ownership-marker creation/verification/safe legacy migration, refusal to claim unrelated pre-existing directories, symlink rejection (target, ancestor, marker, nested symlink), protection of container/lobby/vanilla/active/standby/loaded/plugin-data directories, interrupted `ROTATING` recovery, and state-store validation.
- **Configuration & core lifecycle (`core/`):** Production-safe defaults in shipped `config.yml`, numeric config bounds, low-health threshold validation, replay audio config compatibility, and `RunManager` transitions.
- **Player reset & insomnia (`player/`, `listener/`):** Full player state reset and `Statistic.TIME_SINCE_REST` phantom-insomnia reset on new attempts and joins.
- **Persistence (`stats/`, `participation/`, `telemetry/`):** Atomic persistence, batch participant recording, rename handling, attempts-played counts, empty attempt retention, playtime sessions/checkpoints/shutdown, and death telemetry history.
- **Deathcam & QoL (`replay/`, `qol/`):** Rolling buffers, actor scene reconstruction, camera path/motion, standard Adventure HUD rendering & cleanup (`ReplayCinematicHudTextTest`, `ReplayCinematicViewTest`, verifying zero custom fonts/private-use glyphs even under legacy configs), combat/explosion/terrain/environment timelines, low-health alert policies, and heart display formatting.

---

## Paper Development-Server Regression Checklist

Automated unit tests cannot validate live Minecraft chunk generation, portal travel, client packet rendering, or Paper's shutdown lifecycle. Before cutting a maintenance release, run a clean disposable Paper `26.3` server regression:

### 1. Clean Startup & Diagnostics
- Start on a clean disposable server directory (both **with** and **without** PacketEvents `2.14.0`).
- Confirm `ACTIVE` attempt `#1` and `STANDBY` attempt `#2` initialize cleanly, `.instigatehardcore-owner.properties` exists inside all 6 attempt dimension folders, and `/hc debug` reports `Integrity OK`.
- Confirm `worlds.lobby-world` (`world`) remains separate and untouched.

### 2. Join, Reconnect & Phantom Insomnia
- Join as a player; verify Survival placement in `ACTIVE`, scoreboard assignment, TAB hearts, participant count = `1 / 1`, and `TIME_SINCE_REST` reset to `0`.
- Disconnect and reconnect; verify inventory/location persist and participant count stays `1 / 1`.
- Verify `/hc help`, `/hc status`, `/hc stats`, `/hc deaths`, `/hc coords`, `/hc worlds`, `/hc debug`.
- Verify `/hcreplaytest` is refused when `death-replay.debug-preview-enabled: false`.

### 3. Dimensions & Portal Isolation
- Enter a Nether portal from `ACTIVE` Overworld → confirm arrival in `ACTIVE` Nether and return to `ACTIVE` Overworld.
- Enter an End portal from `ACTIVE` Overworld → confirm arrival in `ACTIVE` End and exit back into `ACTIVE` Overworld.

### 4. Real Death, Deathcam & World Rotation
- **With PacketEvents `2.14.0`:** Trigger a real death in `ACTIVE`. Verify vanilla death chat/drops suppressed, 7s Deathcam plays, 3s reset buffer runs, `STANDBY` promotes to `ACTIVE`, player state/advancements/insomnia reset, retired attempt unloads and deletes cleanly, and replacement `STANDBY` generates.
- **Without PacketEvents:** Repeat a death and verify clean fallback to the standard 10s reset countdown and full world rotation.

### 5. Administrative Reset
- Run `/hc reset` then `/hc reset confirm`. Verify the attempt rotates using the standard countdown without recording a player death, and confirm a stale confirmation cannot reset a newer attempt.

### 6. Restart & Interrupted-Rotation Recovery
- **Normal restart:** Stop cleanly with `/stop` and restart. Confirm attempt number, stats, participation, telemetry, and `ACTIVE`/`STANDBY` worlds recover with `/hc debug` → `Integrity OK`.
- **Interrupted rotation:** Simulate or terminate during `phase=ROTATING` (`world-state.properties`) and restart. Confirm recovery promotes the persisted standby attempt, reconciles stats, generates a new standby, returns `phase=STABLE`, and preserves legacy ownership-marker provenance.

### 7. Protected-World Deletion Safeguards
- Confirm retired attempt folders delete cleanly while `world/` (`lobby`), `world/dimensions/minecraft/*`, `ACTIVE`, `STANDBY`, `plugins/InstigateHardcore`, and any unmarked/unrelated directory under `world/dimensions/instigatehardcore/` remain completely untouched.

---

## Development Rules

- Never hot-reload (`/reload`) the plugin.
- Never delete live player `.dat` files.
- Never move Bukkit/Paper API calls onto asynchronous threads unless explicitly supported by Paper.
- Never bypass `WorldDeletionGuard` or weaken ownership/symlink checks.
- Log errors with `java.util.logging.Logger` (`Level.SEVERE` / `Level.WARNING`) rather than `printStackTrace()`.
- Route player-facing styling through `InstigateTheme`.

---

## Related Documentation

- [`../README.md`](../README.md) — overview, installation, and upgrade steps
- [`../SECURITY.md`](../SECURITY.md) — security policy and data-loss protections
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — system design and lifecycle
- [`ROADMAP.md`](ROADMAP.md) — release history and maintenance policy
