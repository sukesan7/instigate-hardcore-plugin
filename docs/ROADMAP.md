# Development Roadmap

## Phase 0 — Bootstrap

- Java 25
- Gradle
- Paper API
- Git
- Project structure
- Documentation
- Basic plugin load/unload

## Phase 1 — Run State

- RunManager
- Run state machine
- Prevent duplicate reset events
- World/attempt counter

## Phase 2 — Death System

- PlayerDeathEvent listener
- First death ends run
- Death attribution
- Global announcement

## Phase 3 — Persistent Statistics

- Player UUID storage
- Player name cache
- Total deaths
- Total worlds
- Run duration
- Persistent storage

## Phase 4 — Reset Countdown

- Countdown scheduler
- Titles
- Chat messages
- Sounds
- Freeze/end gameplay safely

## Phase 5 — Scoreboard

- World number
- Run timer
- Player death totals
- Dynamic updates

## Phase 6 — Seamless World Rotation

### 6A — Player Transition
- [x] Remove restart-based reset architecture
- [x] Remove reset marker files
- [x] Remove external reset wrapper
- [x] Clear failed-attempt inventories
- [x] Clear Ender Chests
- [x] Clear XP and potion effects
- [x] Auto-respawn run-ending player
- [x] Move all players into spectator mode
- [x] Keep everyone connected
- [ ] Multiplayer integration test

### 6B — World Sets
- [ ] Define WorldSet model
- [ ] Permanent lobby/control world
- [ ] Active attempt world
- [ ] Standby attempt world
- [ ] Random seeds
- [ ] Spawn preparation
- [ ] Persist active/standby metadata

### 6C — World Rotation
- [ ] Promote standby to active
- [ ] Reset player state
- [ ] Teleport all players to new spawn
- [ ] Set new respawn location
- [ ] Restore survival mode
- [ ] Advance attempt number
- [ ] Generate replacement standby

### 6D — World Cleanup
- [ ] Verify no players remain
- [ ] Safely unload old dimensions
- [ ] Delete old world directories
- [ ] Failure handling
- [ ] Lobby fallback

### 6E — Dimension Routing
- [ ] Active Overworld -> active Nether
- [ ] Active Nether -> active Overworld
- [ ] Active Overworld -> active End
- [ ] End exit -> active Overworld
- [ ] Prevent cross-attempt portal travel

### 6F — Recovery
- [ ] Persist active attempt
- [ ] Persist standby attempt
- [ ] Restore worlds after normal server restart
- [ ] Detect incomplete rotations
- [ ] Rebuild missing standby world

### 6G — Integration Testing
- [ ] Solo death reset
- [ ] Simultaneous player deaths
- [ ] Nether death
- [ ] End death
- [ ] Inventory reset
- [ ] Server restart mid-run
- [ ] Server restart during standby generation
- [ ] Repeated world rotations

## Phase 7 — Commands

- /hc
- /hc stats
- /hc status
- /hc reset
- /hc reload

## Phase 8 — Polish

- Configurable messages
- Permissions
- Run history
- Leaderboards
- Better logging
- Failure recovery

## Phase 9 — Testing

- Unit tests
- Test server
- Multi-player death race testing
- Restart failure testing
- Corrupted stats recovery
- Unexpected shutdown testing

## Phase 10 — Release

- GitHub repository
- Release JAR
- Installation guide
- Changelog