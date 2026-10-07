# Roadmap

## Project Status

**Instigate Hardcore** is currently in the final pre-release stage.

The core gameplay architecture is complete:

- shared hardcore attempts;
- seamless world rotation;
- pre-generated standby worlds;
- persistent player statistics;
- participation tracking;
- playtime and death telemetry;
- crash recovery;
- portal isolation;
- administrative reset controls;
- diagnostics;
- automated persistence tests;
- final player-facing visual theme.

The remaining work is primarily release preparation, documentation polish, screenshots, and one final regression pass.

---

# Release Goal

The current target is:

```text
v1.0.0
```

The first stable release should represent a complete, usable shared-hardcore plugin that any Paper server owner can install for a group of friends.

The release should require:

- no client-side mod;
- no manual world replacement between attempts;
- no server restart after a death;
- no fixed player roster.

---

# Completed Milestones

## Phase 0 — Project Bootstrap

Completed:

- Gradle project setup;
- Paper API dependency;
- Java toolchain;
- Gradle wrapper;
- development Paper server;
- plugin metadata;
- repository structure.

---

## Phase 1 — Run State

Completed:

- explicit run state machine;
- `STARTING`;
- `ACTIVE`;
- `ENDING`;
- `RESETTING`;
- run timer;
- guarded transitions.

This created the authoritative lifecycle used by all later systems.

---

## Phase 2 — Shared Death Handling

Completed:

- active-world death detection;
- one death ends the attempt;
- duplicate attempt-ending protection;
- vanilla death-message suppression;
- drop/XP suppression;
- normalized death causes.

---

## Phase 3 — Persistent Statistics

Completed:

- player UUID tracking;
- latest player names;
- total death counts;
- persistent current attempt;
- atomic save flow;
- death leaderboard data.

Persistent file:

```text
stats.properties
```

---

## Phase 4 — Reset Countdown

Completed:

- configurable reset countdown;
- lifecycle callback into world reset;
- countdown cancellation;
- countdown state reporting;
- final themed subtitle countdown.

Current visual style:

```text
Attempt #N · Resetting in 10s
```

---

## Phase 5 — Scoreboard

Completed:

- sidebar scoreboard;
- current attempt;
- run timer;
- death leaderboard;
- configurable refresh interval;
- player assignment on join;
- refresh after attempt rotation;
- final Instigate Cafe theme;
- hidden internal score numbers.

---

## Phase 6 — Seamless World Architecture

Completed:

- permanent lobby world;
- active attempt world set;
- standby attempt world set;
- retired world state;
- Overworld / Nether / End isolation;
- random seeds;
- shared seed across an attempt's dimensions;
- standby pre-generation;
- configurable spawn-area preload;
- automatic standby promotion;
- automatic retired-world cleanup;
- replacement standby generation.

Normal lifecycle:

```text
ACTIVE      Attempt #N
STANDBY     Attempt #N+1

        ↓ attempt ends

RETIRED     Attempt #N
ACTIVE      Attempt #N+1
STANDBY     Attempt #N+2
```

---

## Phase 6 — Persistence and Crash Recovery

Completed:

- persistent world metadata;
- `STABLE` rotation state;
- `ROTATING` rotation state;
- atomic world-state save;
- startup reconciliation;
- incomplete-rotation recovery;
- standby promotion after interrupted rotation;
- attempt-number reconciliation;
- world-state validation.

Persistent file:

```text
world-state.properties
```

---

## Phase 6 — Portal Routing

Completed:

- active Overworld ↔ Nether routing;
- active End routing;
- End exit to current active attempt;
- standby portal protection;
- retired-world portal protection;
- lobby portal protection.

---

## Phase 6 — Player Reset and Safety

Completed:

- inventory reset;
- ender chest reset;
- XP reset;
- health/hunger reset;
- effect cleanup;
- transient-state cleanup;
- countdown spectator state;
- new-attempt player preparation;
- emergency move to permanent lobby.

Live player `.dat` files are not deleted.

---

## Phase 7 — Command Framework

Completed public commands:

```text
/hc
/hc help
/hc status
/hc stats
/hc stats <player>
/hc deaths
```

Completed administrative commands:

```text
/hc reset
/hc reset confirm
/hc worlds
/hc debug
```

Completed:

- tab completion;
- permission checks;
- interactive help;
- clickable player names;
- safe reset confirmation.

---

## Phase 7 — Participation Tracking

Completed:

- unique participants per attempt;
- no fixed roster;
- mid-run joins;
- disconnect persistence;
- attempts-played history;
- player rename handling;
- restart persistence.

Persistent file:

```text
attempt-participants.properties
```

The status denominator represents players who actually participated in that attempt.

---

## Phase 7 — Player Telemetry

Completed:

- per-attempt playtime;
- total playtime;
- active playtime sessions;
- disconnect session closing;
- attempt-end session closing;
- shutdown persistence;
- periodic checkpoints;
- structured death history;
- death cause aggregation;
- latest death history;
- latest player names.

Persistent file:

```text
player-telemetry.yml
```

---

## Phase 7 — Shared Attempt Ending

Completed:

- real deaths and admin resets use the same lifecycle;
- player death records statistics;
- admin reset does not record a death;
- attempt sessions stop before reset;
- confirmed reset is tied to a specific attempt number.

---

## Phase 7 — Diagnostics

Completed:

```text
/hc worlds
/hc debug
```

Diagnostics expose:

- run state;
- persistent rotation phase;
- active attempt;
- standby attempt;
- retired attempt;
- countdown state;
- rotation state;
- cleanup state;
- participant counts;
- integrity warnings.

---

## Phase 7 — Automated Regression Tests

Completed automated coverage for:

- participant uniqueness;
- participant persistence;
- rename handling;
- attempts-played semantics;
- empty-attempt persistence;
- death telemetry persistence;
- latest-death ordering;
- death cause aggregation;
- playtime sessions;
- attempt-specific session closure;
- checkpoint persistence;
- shutdown persistence.

Current verification commands:

```bash
./gradlew clean test
./gradlew clean build
```

---

## Phase 8 — Interface and Theme

Completed:

- centralized `InstigateTheme`;
- azure/purple visual identity;
- neutral gray text hierarchy;
- soft red reserved for actual errors;
- compact `[Instigate Cafe]` chat prefix;
- redesigned command panels;
- redesigned scoreboard;
- redesigned countdown;
- redesigned attempt start/end messages.

New-attempt message:

```text
[Instigate Cafe] Attempt #N has begun. Good luck.
```

---

# Current Phase — v1.0 Release Preparation

The project is now in release-candidate preparation.

## 1. Gallery

Capture final screenshots for:

```text
docs/assets/gallery/scoreboard.png
docs/assets/gallery/status.png
docs/assets/gallery/stats.png
docs/assets/gallery/attempt-end.png
docs/assets/gallery/new-attempt.png
```

Optional supporting screenshots:

```text
docs/assets/gallery/deaths.png
docs/assets/gallery/worlds.png
```

Screenshots should use:

- final theme;
- consistent GUI scale;
- no F3 overlay;
- clean chat;
- realistic stats;
- no unrelated test messages.

---

## 2. README Finalization

Remaining README work:

- insert final screenshots;
- verify Mermaid world-rotation diagram;
- verify installation instructions;
- verify configuration example;
- verify command list;
- verify permissions;
- verify disclaimer;
- verify Paper/Java version information.

The README should remain focused on normal users.

Detailed internals belong in:

```text
docs/ARCHITECTURE.md
docs/DEVELOPMENT.md
```

---

## 3. Documentation Review

Current technical documentation:

```text
docs/ARCHITECTURE.md
docs/DEVELOPMENT.md
docs/ROADMAP.md
```

Before release:

- verify class names against current source;
- verify command names;
- verify data-file names;
- verify lifecycle diagrams;
- remove obsolete implementation notes.

---

## 4. Plugin Metadata Review

Review:

```text
src/main/resources/plugin.yml
src/main/resources/config.yml
```

Confirm:

- plugin name;
- version;
- API version;
- main class;
- command descriptions;
- aliases;
- permissions;
- default permission levels;
- config defaults.

---

## 5. Final Manual Regression

Before tagging `v1.0.0`, complete one full lifecycle test.

### Startup

```text
[ ] server starts without plugin errors
[ ] active attempt loads
[ ] standby attempt loads
[ ] /hc debug reports Integrity OK
```

### Player Join

```text
[ ] player enters active attempt
[ ] player is recorded once
[ ] scoreboard appears
[ ] playtime begins
```

### Commands

```text
[ ] /hc
[ ] /hc help
[ ] /hc status
[ ] /hc stats
[ ] /hc stats <player>
[ ] /hc deaths
[ ] /hc worlds
[ ] /hc debug
```

### Real Death

```text
[ ] one death ends attempt
[ ] death total increases once
[ ] telemetry death is written
[ ] playtime session closes
[ ] countdown appears
[ ] no duplicate death handling occurs
```

### Rotation

```text
[ ] standby becomes active
[ ] all online players move
[ ] player state resets
[ ] participation is recorded
[ ] new telemetry begins
[ ] scoreboard updates
[ ] "Good luck." message appears
```

### Cleanup

```text
[ ] retired worlds unload
[ ] retired directories are removed
[ ] lobby remains
[ ] current active remains
[ ] current standby remains
```

### Replacement Standby

```text
[ ] new standby generates
[ ] standby attempt == active + 1
[ ] /hc debug returns Integrity OK
```

### Admin Reset

```text
[ ] /hc reset requires confirmation
[ ] /hc reset confirm ends the attempt
[ ] no player death is recorded
[ ] stale confirmation cannot reset the next attempt
```

### Persistence

After normal server restart:

```text
[ ] current attempt persists
[ ] deaths persist
[ ] participants persist
[ ] playtime persists
[ ] death history persists
[ ] active/standby worlds recover correctly
```

---

## 6. Crash-Recovery Regression

Before the stable release, complete one intentional hard-crash test during rotation.

Expected recovery:

```text
persisted standby becomes active
attempt number reconciles
replacement standby is created
persistent phase returns to STABLE
players return to valid active gameplay
/hc debug reports Integrity OK
```

This is one of the highest-value release tests because world rotation is the most state-sensitive system in the plugin.

---

## 7. Automated Verification

Final release build:

```bash
./gradlew clean test
./gradlew clean build
```

Both must pass from a clean working tree.

---

## 8. Release Artifact

Verify the built JAR under:

```text
build/libs/
```

The release should contain the plugin JAR only.

Do not ship:

```text
server/
build/
.gradle/
development world folders
local plugin data
IDE metadata
```

---

## 9. Version and Tag

Target:

```text
v1.0.0
```

Suggested release flow:

```bash
git add .
git commit -m "release: prepare v1.0.0"
git push

git tag v1.0.0
git push origin v1.0.0
```

Create the GitHub release from that tag and attach the final plugin JAR.

---

# v1.0 Definition of Done

`v1.0.0` is complete when:

```text
[ ] shared death lifecycle is stable
[ ] seamless world rotation is stable
[ ] standby world is generated automatically
[ ] crash recovery is verified
[ ] portals remain isolated per attempt
[ ] player state does not leak between attempts
[ ] statistics persist
[ ] participation persists
[ ] telemetry persists
[ ] commands are complete
[ ] admin reset is safe
[ ] diagnostics report valid state
[ ] automated tests pass
[ ] manual regression passes
[ ] README is complete
[ ] screenshots are complete
[ ] architecture docs are current
[ ] development docs are current
[ ] release JAR builds cleanly
```

---

# After v1.0

Post-release work should be driven by actual use rather than adding features before the first stable release.

Potential future areas include:

## Configuration Improvements

Possible options:

- configurable display branding;
- configurable theme colors;
- configurable attempt-start text;
- configurable death/reset announcements;
- configurable world preload radius.

These should only be added where customization provides clear value without making the core lifecycle ambiguous.

## Statistics Enhancements

Possible additions:

- longest attempt;
- fastest death;
- longest individual survival time;
- server-wide total playtime;
- attempt history command;
- exportable statistics.

## Administrative Tools

Possible additions:

- explicit maintenance/safe mode;
- controlled retry of standby generation;
- richer integrity report;
- backup hooks before destructive cleanup.

## Release Compatibility

Future Paper/Minecraft versions should be validated individually.

Do not assume compatibility solely because the plugin compiles.

---

# Non-Goals

The following are not required for `v1.0.0`:

- client-side mods;
- custom resource packs;
- database infrastructure;
- web dashboard;
- fixed player roster;
- cross-server synchronization;
- proxy-network support;
- automatic plugin hot reload;
- HFT-style or unnecessarily complex persistence infrastructure.

The goal is a focused shared-hardcore experience, not a general Minecraft server framework.

---

# Roadmap Maintenance

This roadmap is intended to be useful during the final development and first-release cycle.

After `v1.0.0`, there are two reasonable options:

1. keep `ROADMAP.md` for future planned work; or
2. remove it and maintain a public `CHANGELOG.md` instead.

For a stable public repository, `CHANGELOG.md` should become the authoritative history of released user-facing changes.
