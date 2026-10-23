# Minecraft Siege Survival

A cooperative, anchor-based survival siege minigame for **Paper 26.1+**. Players choose a base, optionally prepare for a set number of minutes, and defend against progressively stronger waves of zombies, skeletons and pillagers. When every participating player dies, the match is lost.

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

## Requirements

- Paper server 26.1+ (**not** Spigot, Forge or Folia).
- Java **25** (server and build).
- Gradle 9+ (for local builds; CI installs Gradle).

## Build and install

1. Install JDK 25 and Gradle 9+.
2. Run `gradle clean build` from the repository root.
3. Copy `build/libs/MinecraftSiegeSurvival-*.jar` to a Paper server's `plugins/` directory.
4. Restart the server and edit `plugins/SiegeSurvival/config.yml` as needed.

Never test block-breaking features on a world without backups. The plugin does **not** promise complete terrain restoration.

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
- The initial mining implementation is limited and intentionally conservative; its timing is approximate rather than a verified replication of every vanilla enchantment/tool calculation.
- Block changes are not restored automatically. Do not run an active siege on an irreplaceable base.
- No creative mode during a match; original player game modes and locations are restored on normal cleanup.
- Original game modes and locations are journaled to a server-local recovery file. They are restored on join or post-respawn after interruptions; terrain and inventory are not rolled back.
- Other region protection plugins do not automatically authorize plugin-made block changes. Use an empty test world until integrated.
- Servers running on **Peaceful** are not supported for sieges. Difficulty isn't forcibly changed globally.
- To test concurrency or difficult structures, use a separate Paper test server.

## Development

See [CLAUDE.md](CLAUDE.md) (also exposed via `AGENTS.md`) for coding-agent guidance, feature boundaries and acceptance criteria. Small, testable changes are preferred; the AI/mining controller is the highest-risk component.

### CI

GitHub Actions compiles the plugin on JDK 25 and runs unit tests; this is not a substitute for integration testing in a running Paper server.

## License

[Unlicense](LICENSE). Public-domain dedication as provided by the repository owner.
