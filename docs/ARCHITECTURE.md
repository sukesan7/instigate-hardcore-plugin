# Architecture

## Design Principle

World data is disposable.

Campaign data is permanent.

The Minecraft Overworld, Nether, End, inventories, advancements,
and normal world state may be destroyed during a reset.

InstigateHardcore statistics must survive every reset.

## High-Level Flow

Player Death
    |
    v
Death Listener
    |
    v
Run Manager
    |
    +--> Stats Manager
    |
    +--> Countdown Manager
    |
    +--> Scoreboard Manager
    |
    v
Reset Request
    |
    v
Server Shutdown
    |
    v
External Linux Reset Script
    |
    +--> Remove old worlds
    |
    +--> Generate new world
    |
    v
New Run

## Planned Components

### RunManager

Owns the current run state.

States may include:

- STARTING
- ACTIVE
- ENDING
- RESETTING

Only one run-ending death may be processed.

### DeathListener

Receives player death events and reports the first valid death
to RunManager.

### StatsManager

Owns persistent statistics such as:

- total worlds
- individual player deaths
- run history
- longest run
- shortest run

### CountdownManager

Handles the run-ending countdown.

### ScoreboardManager

Displays persistent and current-run information.

### ResetManager

Safely requests a world reset and server restart.

It should not directly perform unsafe filesystem deletion from arbitrary
plugin code unless explicitly designed and tested to do so.

## Persistence

Persistent plugin information will live outside the Minecraft world
directories.

Initial implementation:

YAML

Potential later implementation:

SQLite