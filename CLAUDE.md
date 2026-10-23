# Coding agent guide — MinecraftSiegeSurvival

This file is the canonical agent context. `AGENTS.md` is a **Git symlink** pointing here; edit this file, not the link.

## Mission and invariants

Paper 26.1+ standalone Java 25 plugin supporting Overworld, Nether, and End. Same selected zombies/skeletons/pillagers in all worlds. One active cooperative siege in an existing survival world. Players prepare without movement restrictions; once wave 1 begins, a configurable circular anchor-centered boundary applies to living participants until the game ends. Mobs prefer the anchor, fight nearby living players, and resume moving toward the anchor. Only tool-equipped mobs may mine whitelisted obstacles. Players have one life; a final wave clear wins, all players eliminated loses.

## Commands and boundaries

- `/siege anchor`, `/siege setup`, `/siege start [waves]`, `/siege start prep [minutes] [waves]`, `/siege radius <blocks>`, `/siege status`, `/siege stop`.
- Do not globally modify game difficulty, weather, time, game rules, or world border.
- Do not interfere with players outside the active siege except for the explicitly server-wide participation contract.
- Don't restrict players during preparation except initial teleport to initiator; on wave 1 return players from other dimensions/outside the radius to anchor.
- Use server-thread Bukkit/Paper APIs only on proper scheduler threads.
- Validate all match state changes; clean up tasks and tagged mobs on stop.
- Only blocks within the mining/destruction radius may be altered by custom AI; never break inventories, protected blocks or unknown materials.
- Player and world backups are essential. Mob-mined simple block changes use the append-only BlockJournal with best-effort reverse restoration; no blanket rollback promises.

## Source layout and design

`com.bymatej.siege`: plugin bootstrap; `game`: match state; `command`: commands and menus; `wave`: spawn and scaling; `ai`: target/mine policy; `listener`: boundary, death and world events. Avoid adding NMS unless absolutely necessary and separately version-gated.

## Testing priorities

1. A command-free and restarted server remain unchanged; safe startup/shutdown.
2. 1-player and multi-player state transitions and win/lose conditions.
3. Preparation is unrestricted; boundary activates exactly at first wave, for walk/teleport/vehicle/world changes; spectator safety.
4. Health starts at 10%, ends at 150%, and is properly capped for a 1-wave match.
5. Natural spawns around the anchor suppressed without altering global gamerules.
6. Correct mob registration, incremental spawning and cleanup.
7. Doors, tool eligibility, protected blocks, no mining outside the configured zone, and pathfinding under obstructed structures.
8. Survival mode restoration, reconnect handling and server crash recovery.
9. Nether roof/void, cave floors and End islands: validate safe spawn elevations and never spawn into unsupported spaces.

## Development discipline

- Implement small, reviewable milestones. Do not claim features are production ready without compiling and Paper integration testing.
- Java 25 + Gradle Kotlin DSL. Default local Paper API: `26.1.2.build.74-stable`; CI also validates `26.2.build.129-stable`.
- Release builds pass `-PminecraftVersion=<MC>`, `-PpluginVersion=<MSS>` and `-PpaperApiVersion=<MC>.build.<N>-stable`. The JAR and release tag are both `MSS-<MC>.<MSS>`; the expanded `plugin.yml` must contain the selected versions.
- Releases are published manually using `.github/workflows/release.yml`. Never auto-publish from an ordinary push, overwrite a prior tag, or embed tokens in tracked files.
- Prefer Paper API over NMS. Do not silently disable permissions or protection integrations.
- Update README when features or limitations change; add unit tests for deterministic logic.
- Never commit secrets, tokens, private keys, world saves, local test-server directories or player-specific files. `.gitignore` is defense-in-depth, not permission to stage secrets.
