# Roadmap & Maintenance Status

## Project Status

**Instigate Hardcore** (`v1.0.5`) has fulfilled its original gameplay objectives and is now in **maintenance mode**.

All planned shared-hardcore systems are implemented and shipped:

- shared-death hardcore attempts across Overworld, Nether, and End;
- seamless active/standby/retired world rotation without server restarts;
- transactional persistence (`STABLE` / `ROTATING`) and interrupted-rotation recovery;
- hardened world-deletion safeguards (canonical containment, Paper layout validation, ownership markers, symbolic-link protections, and safe existing-installation migration);
- optional seven-second packet-only Deathcam playback via PacketEvents (actors, combat animations, creeper explosions, terrain/environment replay, cinematic tracking camera, and audio);
- persistent statistics, per-attempt participation tracking, configurable late-joiner admission, and playtime/death telemetry;
- per-attempt player resets (inventory, ender chest, XP, vitals, effects, advancements, and phantom insomnia);
- quality-of-life features (paginated sidebar scoreboard, two-tier low-health alerts, TAB health display, `/hc coords`);
- administrative controls (`/hc reset`, `/hc worlds`, `/hc debug`) and automated unit/security regression tests.

---

## Release History

### `v1.0.0` — Initial Public Release
- Core shared-hardcore state machine (`RunManager`, `AttemptEndManager`).
- Seamless three-dimension world pipeline (`WorldSetManager`, `WorldRotationManager`, `WorldCleanupManager`, `WorldRecoveryManager`, `WorldStateStore`) and portal isolation (`PortalRoutingListener`).
- Persistent statistics, participation history, playtime/death telemetry, scoreboard, reset countdown, commands, diagnostics, and `InstigateTheme` UI.

### `v1.0.1` — Deathcam & Quality-of-Life Update
- Seven-second packet-only Deathcam recording, combat animation reconstruction, creeper explosion timing, cinematic camera tracking, and automatic shared playback with PacketEvents.
- Per-attempt advancement resets, paginated death leaderboard scoreboard, and low-health alerts.

### `v1.0.2` — Terrain Replay & QoL Polish
- Historical terrain and bounded environmental change replay (buckets, fluids, pistons, doors, redstone, explosions) and replay audio/crouch improvements.
- Two-tier low-health alerts with critical alarm, TAB player-list hearts, and `/hc coords` clickable coordinate broadcasts.

### `v1.0.3` — Phantom Insomnia Hotfix
- Reset `Statistic.TIME_SINCE_REST` on new attempts and fresh joins so players do not encounter premature phantoms across attempts.

### `v1.0.4` — Maintenance & Public Release Hardening
- **World deletion hardening:** Added `WorldOwnershipMarker` and `WorldDeletionGuard` enforcing canonical filesystem containment, Paper `26.3` nested dimension & flat layout verification, symlink rejection, safe legacy ownership-marker migration, explicit protection of lobby/vanilla/active/standby/loaded/plugin-data directories, accurate async completion tracking, and graceful shutdown handling.
- **Production defaults & lifecycle hardening:** Disabled `death-replay.debug-preview-enabled` by default while preserving normal Deathcam playback, kept experimental resource-pack HUD disabled, added centralized numeric `ConfigBounds` validation, added defense-in-depth permission checks, replaced `printStackTrace()` calls with structured logging, and closed scheduler cancellation gaps.
- **Targeted performance improvements:** Eliminated redundant location/block allocations in `DeathReplayRecorder` and `ReplayBlockRecorder`, diffed sidebar updates in `HardcoreScoreboardManager`, and batched rotation participant saves into a single atomic write.
- **Build & supply-chain integrity:** Added read-only Java `25` GitHub Actions CI with SHA-pinned actions, Gradle `9.8.0` wrapper SHA-256 pinning, `gradle/verification-metadata.xml` dependency integrity verification, scoped Maven repositories (`exclusiveContent`), and packaged `META-INF/LICENSE.md` in release JARs.
- **Documentation & security policy:** Added `SECURITY.md` and refreshed `README.md`, `ARCHITECTURE.md`, `DEVELOPMENT.md`, and `ROADMAP.md` to match implemented functionality and maintenance status.

### `v1.0.5` — Deathcam HUD Compatibility Hotfix
- **Retired unfinished custom resource-pack HUD:** Removed `ResourcePackReplayHud` (private-use Unicode glyphs and custom font identifiers `instigatehardcore:deathcam` / `instigatehardcore:deathcam_names` that had no distributed client resource pack) so standard Minecraft Adventure components (purple progress boss bar, `DEATHCAM` title + victim name, action-bar replay timer) are the sole Deathcam renderer on all vanilla and compatible clients.
- **Safe legacy configuration handling:** Removed `death-replay.cinematic.resource-pack-hud-enabled` from shipped `config.yml`; legacy configs containing `resource-pack-hud-enabled: true` are safely ignored with a single startup warning and can no longer trigger missing-glyph boxes.
- **HUD lifecycle hardening & regression tests:** Separated title/action-bar cleanup error isolation in `ReplayCinematicView`, cleared non-cinematic `"INSTANT REPLAY"` action bars on `DeathReplayPlaybackService` viewer teardown, and added unit regression coverage verifying standard font rendering and cleanup across fresh and legacy configurations.

---

## Maintenance Policy

Going forward, maintenance work is intentionally limited to:

- security vulnerabilities and data-safety fixes;
- critical gameplay or crash-recovery bug fixes;
- compatibility verification when Paper `26.3.x` updates affect the plugin.

New gameplay features are **not** planned.

---

## Explicit Non-Goals (Not Implemented)

The following features are **not** implemented and are explicitly out of scope for maintenance mode:

- **Death voting** or vote-to-reset mechanics;
- **Attempt whitelists** or fixed player rosters (use Paper's built-in server whitelist and `players.allow-late-joiners` instead);
- Client-side mods or custom resource packs;
- External SQL/NoSQL database backends or web dashboards;
- Cross-server or proxy-network synchronization;
- Runtime `/reload` hot-reloading.

---

## Related Documentation

- [`../README.md`](../README.md) — user guide, installation, and configuration
- [`../SECURITY.md`](../SECURITY.md) — security reporting and operational safeguards
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — system design and invariants
- [`DEVELOPMENT.md`](DEVELOPMENT.md) — local development and regression checklist
