# Development

## Overview

This document describes the local development workflow for **Instigate Hardcore**.

The project is a Paper plugin written in Java and built with Gradle.

The normal workflow is:

```text
edit
  ↓
unit tests
  ↓
build
  ↓
run local Paper server
  ↓
manual regression
  ↓
commit
```

The local Paper server is disposable development infrastructure.

Do not use a production server as the primary test environment for world-rotation changes.

---

# Current Toolchain

The project currently targets:

```text
Minecraft / Paper    26.3
Java toolchain       25
Gradle Wrapper       9.8.0
JUnit                6.1.2 / Jupiter
```

The developer shell may run a newer JDK, but Gradle compiles the project using the configured Java toolchain.

The exact Paper dependency version should remain consistent across compile and test configurations.

---

# Repository Layout

```text
instigate-hardcore/
├── README.md
├── LICENSE
├── .gitignore
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
│
├── gradle/
│   └── wrapper/
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DEVELOPMENT.md
│   ├── ROADMAP.md
│   └── assets/
│       └── gallery/
│
├── scripts/
├── server/
│
└── src/
    ├── main/
    │   ├── java/
    │   │   └── dev/instigatehardcore/
    │   └── resources/
    │       ├── plugin.yml
    │       └── config.yml
    │
    └── test/
        └── java/
            └── dev/instigatehardcore/
```

`server/` is used by the local Paper development server.

Generated server data should not be committed.

---

# Build Commands

## Run Tests

```bash
./gradlew clean test
```

Use this after logic or persistence changes.

---

## Build Plugin

```bash
./gradlew clean build
```

The plugin JAR is written under:

```text
build/libs/
```

---

## Run Development Server

```bash
./gradlew runServer
```

Use this for world generation, portal testing, death flow, scoreboard rendering, titles, commands, player movement, and crash-recovery testing.

---

# Gradle Dependency Model

Paper should remain server-provided for the actual plugin.

```kotlin
compileOnly("io.papermc.paper:paper-api:...")
```

The test suite also uses Paper/Bukkit classes such as `YamlConfiguration`, so tests need the same Paper API on the test runtime classpath:

```kotlin
testImplementation("io.papermc.paper:paper-api:...")
```

Do not replace `compileOnly` with `implementation`.

```text
compileOnly
    → compile plugin against Paper
    → Paper server provides API at runtime

testImplementation
    → JUnit receives Paper/Bukkit classes during tests
```

---

# Configuration

Development defaults are stored in:

```text
src/main/resources/config.yml
```

Current major settings:

```yaml
reset:
  countdown-seconds: 10

scoreboard:
  enabled: true
  update-interval-ticks: 20

worlds:
  lobby-world: world
  preload-radius-chunks: 1

debug:
  enabled: false
```

When adding configuration:

1. choose a stable key;
2. provide a safe default;
3. load it centrally;
4. validate unsafe values;
5. update the README if users need to know about it.

Avoid configuration for internal implementation details that should remain deterministic.

---

# Plugin Metadata

`plugin.yml` defines the public command and permission surface.

Primary command:

```text
/hardcore
```

Alias:

```text
/hc
```

Permissions:

```text
instigatehardcore.command
instigatehardcore.admin
instigatehardcore.debug
```

Do not add `/reload` support.

Hot reloads are not a safe lifecycle for a plugin that owns generated worlds, scheduler tasks, telemetry sessions, scoreboards, attempt state, and recovery metadata.

Use a full server restart.

---

# Coding Structure

The project favors small manager/listener classes over one large plugin class.

```text
main plugin class
    → lifecycle wiring

manager
    → owns subsystem state / behavior

listener
    → translates Bukkit event into subsystem action

record
    → immutable data model
```

Avoid putting substantial gameplay behavior directly inside `InstigateHardcore`.

---

# UI and Theme

All player-facing styling should use:

```text
dev.instigatehardcore.ui.InstigateTheme
```

Do not introduce new legacy hard-coded colors for normal UI.

The intended palette is:

```text
Azure       primary brand / headings
Purple      secondary accent / attempts
Light gray  important values
Gray        labels
Muted gray  secondary information
Soft red    real errors / destructive warnings
```

Ordinary plugin-originated chat messages should use:

```text
[Instigate Cafe]
```

through:

```java
InstigateTheme.chat(...)
```

Structured command panels do not need the prefix on every line.

Use the theme helpers instead of duplicating styling logic.

---

# User-Facing Text Rules

Keep messages concise, neutral, and consistent with sentence case.

Prefer:

```text
Attempt #15 has begun. Good luck.
```

over:

```text
ATTEMPT #15 HAS STARTED!!!
```

Technical details belong in server logs and `/hc debug`, not normal player chat.

---

# World Naming

Attempt worlds use deterministic names based on attempt number and dimension.

```text
attempt_000012_overworld
attempt_000012_nether
attempt_000012_end
```

Do not casually change this scheme.

World naming is part of persistence, recovery, cleanup safety, diagnostics, and operator expectations.

---

# Persistent Files

Important development data:

```text
stats.properties
attempt-participants.properties
player-telemetry.yml
world-state.properties
```

Do not manually edit them during a regression run unless the test specifically requires inconsistent state.

For clean-state testing, stop the server first and remove test data deliberately.

---

# Unit Testing

Tests live under:

```text
src/test/java/dev/instigatehardcore/
```

## Participation Coverage

Tests should cover:

- unique UUID per attempt;
- repeated joins do not increase count;
- latest name updates;
- first-join timestamp is preserved;
- multiple attempts count correctly;
- skipped attempts do not count as played;
- persistence across reload;
- empty attempts survive reload.

## Telemetry Coverage

Tests should cover:

- death history persists;
- newest deaths are returned first;
- death causes aggregate correctly;
- same-attempt `beginSession` is idempotent;
- per-attempt playtime remains separate;
- ending one attempt closes matching sessions only;
- checkpoints persist elapsed time;
- shutdown persists sessions;
- latest player name persists.

---

# Test Design Guidelines

Prefer deterministic tests.

When filesystem behavior is under test, use JUnit temporary directories:

```java
@TempDir
Path tempDirectory;
```

Tests must not write into `server/` or the real plugin data directory.

---

# Manual Regression

The unit suite cannot validate Minecraft rendering or the complete world lifecycle.

Before a release candidate, perform the following Paper regression.

## Fresh Startup

Verify:

```text
ACTIVE world exists
STANDBY world exists
/hc debug → Integrity OK
```

The lobby must remain separate.

## Join Flow

Verify:

```text
player enters ACTIVE attempt
player is Survival
scoreboard appears
participant count increases only once
telemetry session begins
```

Disconnect and reconnect.

The attempt denominator must not duplicate the same UUID.

## Status and Stats

Test:

```text
/hc
/hc help
/hc status
/hc stats
/hc stats <player>
/hc deaths
```

Check the final azure/purple theme, clickable player names, help interactions, spacing, and absence of visible sidebar score numbers.

## Real Death

Cause a real player death inside the active attempt.

Verify:

```text
one death ends the attempt
vanilla death broadcast is suppressed
[Instigate Cafe] prefix is used
death cause is recorded
death total increases exactly once
telemetry stops
countdown appears
```

Expected chat shape:

```text
[Instigate Cafe] Attempt #N has ended.
[Instigate Cafe] <death message>
[Instigate Cafe] <player> now has X total deaths.
[Instigate Cafe] Next attempt in 10s.
```

## Countdown

Verify:

```text
Attempt #N · Resetting in 10s
```

It should use the smaller subtitle layer.

Check that it updates once per second and clears when rotation begins.

## Rotation

When the countdown completes, verify:

```text
STANDBY becomes ACTIVE
old ACTIVE becomes RETIRED
stats attempt advances once
all online players move to new attempt
player state is cleared
new participation is recorded
new telemetry sessions begin
scoreboard updates
```

Expected message:

```text
[Instigate Cafe] Attempt #N has begun. Good luck.
```

## Replacement Standby

Shortly after activation, verify:

```text
new STANDBY exists
STANDBY attempt == ACTIVE + 1
```

Use:

```text
/hc worlds
/hc debug
```

## Retired Cleanup

Verify the old End, Nether, and Overworld unload and are eventually deleted.

The lobby, current active, and current standby must remain untouched.

## Administrative Reset

Run:

```text
/hc reset
/hc reset confirm
```

Verify the attempt ends without adding a player death or structured death record.

A stale confirmation from an older attempt must not reset a newer attempt.

## Restart Persistence

Stop Paper normally and restart.

Verify:

```text
current attempt persists
death totals persist
participation persists
playtime persists
latest deaths persist
ACTIVE/STANDBY pipeline recovers
/hc debug → Integrity OK
```

---

# Crash-Recovery Testing

Crash recovery should be tested intentionally before major releases or after world-state changes.

Suggested sequence:

1. start a normal attempt;
2. trigger an attempt end;
3. let countdown/rotation begin;
4. terminate the server process during transition;
5. restart;
6. inspect `/hc worlds`;
7. inspect `/hc debug`.

Expected result:

```text
persisted STANDBY is promoted
attempt number reconciles
new STANDBY is created
persistent phase returns to STABLE
players re-enter valid ACTIVE gameplay
```

Do not use graceful `/stop` when specifically testing hard-crash recovery.

---

# Debugging Commands

## `/hc worlds`

Inspect:

```text
ACTIVE
STANDBY
RETIRED
persistent rotation phase
world names
attempt numbers
seeds
```

## `/hc debug`

Inspect:

```text
run state
persistent phase
stats attempt
active attempt
standby attempt
retired attempt
countdown
rotation
cleanup
online players
active players
participant count
integrity warnings
```

Capture both outputs before manually changing persistent data.

---

# Server Logs

Runtime failures should include enough context to diagnose lifecycle state.

Prefer logs containing attempt number, player name when relevant, expected value, actual value, and subsystem action.

Example:

```text
[Instigate Cafe] Standby attempt mismatch. Expected #14 but found #15.
```

Player chat should remain concise even when server logs are detailed.

---

# Scoreboard Development

The scoreboard is intentionally compact.

It prioritizes:

```text
attempt
run timer
death leaderboard
```

When modifying it:

- stay within the sidebar line limit;
- keep underlying score entry keys unique;
- preserve blank visible numeric formatting;
- verify long player names visually;
- do not turn the sidebar into a diagnostics panel.

---

# Countdown and Titles

Adventure titles provide a main title, subtitle, and timing but not arbitrary percentage font scaling.

The countdown therefore uses only the subtitle layer so it appears smaller.

Exact custom scaling would require a resource pack.

Keep titles limited to lifecycle moments.

---

# Adding a New Command

When adding a command:

1. classify it as public, admin, or debug;
2. add permission handling;
3. add tab completion;
4. use `InstigateTheme`;
5. add it to `/hc help`;
6. update `plugin.yml` if needed;
7. update the README if public;
8. add tests if it changes persistent state.

Commands should not bypass lifecycle managers.

---

# Adding Persistent State

Before introducing a new persistent file or field, decide:

- who owns it;
- when it is written;
- whether writes must be atomic;
- how older files migrate;
- how malformed input is handled;
- whether crash consistency matters.

Do not create a second source of truth for data already owned elsewhere.

---

# Release Regression

Before producing a release JAR:

```bash
./gradlew clean test
./gradlew clean build
./gradlew runServer
```

Complete at minimum:

```text
join
status
stats
death
countdown
rotation
standby regeneration
cleanup
admin reset
restart persistence
debug integrity
```

For world-state changes, also complete a hard-crash recovery test.

---

# Gallery Capture

README screenshots live under:

```text
docs/assets/gallery/
```

Recommended files:

```text
scoreboard.png
status.png
stats.png
attempt-end.png
new-attempt.png
deaths.png
worlds.png
```

Before capturing:

- use the final theme;
- use the same client GUI scale;
- hide F3/debug overlays;
- avoid unrelated chat clutter;
- use realistic test data;
- keep each image readable.

The README should use only the strongest screenshots.

---

# Git Workflow

Typical checkpoint:

```bash
git add .
git commit -m "feat: describe change"
git push
```

Useful prefixes:

```text
feat:
fix:
test:
docs:
refactor:
chore:
```

Prefer small commits aligned to one coherent change.

---

# Release Preparation

Before the first stable release:

1. complete the full regression;
2. finalize README screenshots;
3. verify README installation instructions;
4. verify `plugin.yml`;
5. verify default `config.yml`;
6. remove temporary development-only logging;
7. ensure test server data is not tracked;
8. build the final JAR;
9. inspect `build/libs/`;
10. create a release tag.

Suggested first stable version:

```text
v1.0.0
```

After the first public release, maintain a `CHANGELOG.md` for user-facing changes.

`ROADMAP.md` can then remain for future planning or be removed if it no longer adds value.

---

# Common Development Rules

Do not:

- hot reload the plugin;
- delete live player data files;
- mutate attempt worlds outside world managers;
- directly advance attempt state from unrelated classes;
- use directory names as the only cleanup safety check;
- bundle Paper into the plugin;
- introduce player-facing colors outside `InstigateTheme`;
- use a fixed player roster for participation.

Do:

- route lifecycle changes through managers;
- keep persistence ownership clear;
- validate attempt numbers;
- prefer recoverable transitions;
- run tests before builds;
- manually test world changes on Paper;
- keep player-facing messages concise.

---

# Final Verification Checklist

```text
[ ] ./gradlew clean test passes
[ ] ./gradlew clean build passes
[ ] plugin starts without errors
[ ] ACTIVE and STANDBY are valid
[ ] /hc debug reports Integrity OK
[ ] scoreboard uses final theme
[ ] countdown uses final theme
[ ] death ends attempt exactly once
[ ] admin reset adds no death
[ ] new attempt says "Good luck."
[ ] participation behaves correctly
[ ] telemetry survives restart
[ ] retired worlds clean up
[ ] replacement standby generates
[ ] crash recovery was tested after world-state changes
[ ] README screenshots match current UI
```

---

# Related Documentation

- [`../README.md`](../README.md) — installation and public overview
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — system design and lifecycle
- [`ROADMAP.md`](ROADMAP.md) — current development status
