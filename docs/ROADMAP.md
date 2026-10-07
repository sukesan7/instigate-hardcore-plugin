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

## Phase 6 — World Reset

- Shutdown request
- Reset marker
- Linux wrapper
- Overworld deletion
- Nether deletion
- End deletion
- Fresh random seed
- Automatic restart

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