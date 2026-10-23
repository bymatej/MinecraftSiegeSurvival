# Minecraft Siege Survival

A cooperative, anchor-based survival siege minigame for **Paper 26.1+**, with Overworld, Nether and End arenas. Players choose a base, optionally prepare for a set number of minutes, and defend against progressively stronger waves of zombies, skeletons and pillagers. When every participating player dies, the match is lost.

> **Project status:** under active development. The code and README distinguish implemented features from experimental features. Use a backed-up test world; this plugin is not yet production hardened.

## Features and gameplay

- Select an **anchor** at the block you stand on; mobs advance toward that position and fight players they encounter.
- Choose enemy types from an in-game menu, independently enabling zombies, skeletons and pillagers.
- Start immediately or provide a **preparation timer** to collect resources. All online players join at the initiator's location.
- Run a configurable number of waves (default **25**) with scaling population, gear, damage and health.
- Mob maximum/current health scales from **10%** in the first wave to a capped **150%** in the last wave.
- Zombies and skeletons don't burn in daylight.
- Death eliminates a participant for the rest of the match; after respawning, they become a spectator.
- Natural hostile spawning is suppressed only near the active arena.
- During **preparation**, movement is **unrestricted**. From the first wave through intermissions, participants remain within a configurable **200-block circular radius** of the anchor.
- Siege mobs attempt to navigate toward the anchor, handle doors and breach some obstructing blocks when suitably equipped. **Complex indoor navigation/mining is experimental**; do not assume arbitrary fortresses can be breached.

## Nether and End rules

The **same chosen enemy types** (zombies, skeletons and pillagers) spawn in all dimensions. Starting a match in the Nether or End creates an arena *in that world*; no automatic replacement with blazes, piglins or endermen occurs.

Spawns are selected near the anchor's elevation (within 24 vertical blocks), on solid, safe ground with two air blocks of clearance. This avoids the Nether bedrock roof and End void, and does not create platforms or bridge empty space. Small End islands, disconnected islands, caves without safe space, and lava fields may offer too few spawn sites. The plugin will **abort the wave rather than spawn mobs into the void or silently award victory**.

During preparation players may freely travel anywhere, including other dimensions. As wave 1 begins, anyone outside the circular boundary or in another dimension is returned to the anchor. Portals and teleports outside the arena are blocked for the rest of the siege.

## Requirements

- Paper server 26.1+ (**not** Spigot, Forge or Folia).
- Java **25** (server and build).
- Gradle 9+ (for local builds; CI installs Gradle).

## Build and install

1. Install JDK 25 and Gradle 9+.
2. Run `gradle clean build` from the repository root.
3. Copy the matching `build/libs/MSS-*.jar` to a Paper server's `plugins/` directory.
4. Restart the server and edit `plugins/SiegeSurvival/config.yml` as needed.

### Building for a specific Minecraft version

The Minecraft/Paper version and the plugin version are independent. For example, the following builds plugin **1.1** against **Paper 26.2**, embedding these values into `plugin.yml` and producing `MSS-26.2.1.1.jar`:

```sh
gradle clean build -PminecraftVersion=26.2 -PpluginVersion=1.1 -PpaperApiVersion=26.2.build.129-stable
```

Pin a **real stable Paper API build for that Minecraft version**; changing the artifact filename alone does not make a plugin compatible with a new Minecraft version.

### Publishing a GitHub Release

1. Ensure `main` passes CI and the target Paper version has been tested on an actual server.
2. Open **[Actions → Publish versioned release](https://github.com/bymatej/MinecraftSiegeSurvival/actions/workflows/release.yml)**.
3. Click **Run workflow**, select branch `main`, then enter:
   - **Minecraft version:** `26.2`
   - **Plugin version:** `1.1`
   - **Paper build:** `129-stable` (must match the target Minecraft version)
   - **Pre-release:** enabled until gameplay has passed Paper integration tests.
4. The workflow validates the inputs, builds on Java 25, runs the tests, checks `plugin.yml` inside the JAR, and creates a GitHub Release.

**Exact example:**

| Release property | Value |
|---|---|
| Git tag | `MSS-26.2.1.1` |
| Release title | `MSS-26.2.1.1` |
| Downloaded JAR | `MSS-26.2.1.1.jar` |
| In-game plugin version | `1.1` |
| Minecraft API version | `26.2` |

**Beta example:** Select Minecraft `26.2`, plugin `0.1-beta`, Paper build `129-stable`, with **Pre-release** enabled. This generates `MSS-26.2.0.1-beta.jar` with tag/title `MSS-26.2.0.1-beta`. The prefix is **MSS**, not MMC.

Normal commits never publish releases. Releases can be started manually from Actions; a reviewed beta may also be published from an explicitly named `release/MSS-26.2.0.1-beta` branch. Such release branches always publish a pre-release. Existing release tags are never overwritten. It uses GitHub's ephemeral `GITHUB_TOKEN` with only the required repository content permission; no personal token or secret is committed.

Never test block-breaking features on a world without backups. Mob-mined simple blocks are journaled for best-effort restoration on match end/restart, but the plugin does **not** promise complete terrain rollback.

## Commands

| Command | Description |
|---|---|
| `/siege setup` | Open the enemy-selection menu. |
| `/siege anchor` | Save the block beneath your feet as the anchor. |
| `/siege start [waves]` | Start immediately with the saved anchor, or current location if none exists. |
| `/siege start prep [minutes] [waves]` | Begin an unrestricted preparation phase; first wave starts when the timer ends. |
| `/siege radius <blocks>` | Set the movement radius for the next match. |
| `/siege status` | Show state, wave, and living participants. |
| `/siege stop` | Stop the match and clean up. |
| `/siege help` | Command summary. |

By default, controlling a match requires `siege.admin` (OPs have it by default), configurable via `permissions.require-admin`. Status is visible to players.

## Match lifecycle

`IDLE → PREPARING (optional) → WAVE → INTERMISSION → WAVE … → VICTORY/DEFEAT → IDLE`.

Movement boundary is **off** before the first wave, including throughout preparation. At the first wave players outside the radius are teleported to the anchor; teleportation and vehicle travel are also restricted during active play. This does not change the world's global border. Dead players spectate; newly arriving players spectate until the next round.

## Safety and limitations

- One match per server. Match participants are the online players at the start. The plugin does not create an isolated world.
- A match can change the **existing survival world**. The radius restricts where the plugin itself breaks blocks; it is not an anti-grief plugin for ordinary players.
- Mining supports a bounded *same-height* wall-breaching planner and Paper's block destroy speed for tool-based timing. Complex 3D mazes remain experimental. Mining is **enabled by default** (`mining.enabled: true`) for the intended gameplay, but modifies your existing survival world; disable it for protected/irreplaceable bases and always back up first.
- Mob-mined whitelisted blocks are recorded before removal and restored at cleanup/restart when still air (enabled by `arena.rollback-mob-damage`). Player edits, gravity, flowing liquids and other updates cannot be reliably reversed. Do not run an active siege on an irreplaceable base.
- No creative mode during a match; original player game modes and locations are restored on normal cleanup.
- Original game modes and locations are journaled to a server-local recovery file. They are restored on join or post-respawn after interruptions; terrain and inventory are not rolled back.
- Region-claim integrations are not yet implemented. By default, mining refuses to modify blocks at all when known protection plugins (WorldGuard, GriefPrevention, Lands, Towny, Residence, GriefDefender, ClaimChunk) are installed. This is fail-closed, not full integration.
- Servers running on **Peaceful** are not supported for sieges. Difficulty isn't forcibly changed globally.
- To test concurrency or difficult structures, use a separate Paper test server.

## Development

See [CLAUDE.md](CLAUDE.md) (also exposed via `AGENTS.md`) for coding-agent guidance, feature boundaries and acceptance criteria. Small, testable changes are preferred; the AI/mining controller is the highest-risk component.

### CI

GitHub Actions builds against pinned Paper 26.1 and 26.2 APIs on JDK 25, runs unit tests, and includes a Paper 26.2 server smoke test. Real multiplayer, Nether/End pathfinding, and world-damage recovery still require dedicated live playtesting.

## License

[Unlicense](LICENSE). Public-domain dedication as provided by the repository owner.
