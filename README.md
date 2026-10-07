# InstigateHardcore

A multiplayer hardcore survival plugin for Paper.

InstigateHardcore turns a Minecraft server into a shared hardcore run:
if one player dies, the run ends for everyone.

The server records persistent statistics between worlds and automatically
prepares a fresh world for the next attempt.

## Planned Features

- Shared-death hardcore gameplay
- Global death detection
- Reset countdown
- Fresh random seed after each failed run
- Overworld, Nether, and End reset
- Persistent player death totals
- Persistent world/attempt counter
- Sidebar scoreboard
- Current run timer
- Run history
- Administrative commands
- Safe Linux server restart/reset workflow

## Platform

- Minecraft Java Edition
- Paper 26.3
- Java 25
- Gradle 9.8

No client-side mod is required.

Players can connect using Lunar Client, the vanilla Minecraft launcher,
Prism Launcher, or any compatible Java Edition client.

## Development

Build the plugin:

```bash
./gradlew build