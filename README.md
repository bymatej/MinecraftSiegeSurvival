# Minecraft Siege Survival

A cooperative, anchor-based survival siege minigame for **Paper 26.1+**, with Overworld, Nether and End arenas. Players choose a base, optionally prepare for a set number of minutes, and defend against progressively stronger waves of enemies - from plain zombies to blazes, creepers and an optional ravager. When every participating player dies, the match is lost.

> **Project status:** under active development. The code and README distinguish implemented features from experimental features. Use a backed-up test world; this plugin is not yet production hardened.

## Features and gameplay

- Select an **anchor** at the block you stand on; the mobs hunt the **players** and use the anchor as the fallback and as the spawn area.
- Choose enemy types from an in-game menu: **zombies, skeletons, pillagers, vindicators, wither skeletons, piglin brutes, blazes, creepers and (optionally) a ravager**. Every type unlocks in a fixed wave, so the siege escalates gradually instead of throwing the same mobs at you from wave 1.
- Start immediately or provide a **preparation timer** to collect resources. All online players join at the initiator's location.
- Run a configurable number of waves (default **10**) with scaling population, gear, damage and health.
- Mob maximum/current health scales from **30%** in the first wave to a capped **150%** in the last wave.
- Zombies and skeletons don't burn in daylight. Siege enemies are also immune to **environmental damage** (falls, fire, lava, starvation, suffocation, the void): their health is scaled down to 30 % in the first wave, so a single fall would otherwise kill them and empty the wave on its own. Only combat damage applies - a creeper still dies in its own blast. A **`/kill`** of the admin applies as well, so an enemy can always be removed by hand.
- The enemy count scales with the number of players: 1 player keeps the base count, 2 players ×1.3, 3 players ×1.5, 4 players ×1.7 and so on (+0.2 per extra player). The result is rounded **down** for an even number of players and **up** for an odd one.
- Death eliminates a participant for the rest of the match: they skip the respawn screen and become a spectator right away, watching the survivors until the match ends.
- A player who **joins during a match** is put into spectator mode and moved out of the arena (the world spawn, when it lies outside the boundary). They get a message with the current wave and the number of players still in the fight, so they can judge whether it is worth waiting for the next match.
- Natural hostile spawning is suppressed only near the active arena.
- During **preparation**, movement is **unrestricted**. From the first wave through intermissions, participants remain within a configurable **60-block circular radius** of the anchor.
- Siege mobs **intrude**: they hunt the **players**, not the anchor - a player who hides behind a wall or inside a 1x1 box is found anyway. They walk toward their target, **open closed doors, trapdoors and fence gates** on the way (both halves of a door, so tall mobs fit through), **climb ladders**, **jump gaps**, **bridge trenches** and **breach walls** with the tools they carry. A mob that cannot make progress looks for a way in and starts breaking the whitelisted blocks in its way - it keeps breaking until it can actually see the player. The mobs break blocks like players do: bare hands get through dirt and wood, a pickaxe gets through stone, and the right tool is much faster. **Complex indoor navigation/mining is experimental**; do not assume arbitrary fortresses can be breached.
- Waves are separated by a **10 second** intermission. The last **5 seconds** before a wave start are counted down on screen.
- While a wave runs, the **action bar** shows the enemy counters: wave, enemies spawned and enemies left. Chat only carries the countdown and the meaningful events (wave start, wave cleared, outcome); the optional periodic counter line is off by default (`game.status-interval-seconds`).
- The chunks of the **arena** (the circle around the anchor, up to `arena.combat-radius`) are kept loaded while a siege runs, so no enemy can slip into an unloaded chunk, turn invisible and stall a wave. Enemies that fall into the void or into lava are brought back to the anchor - and an enemy that wanders **outside the arena** is brought back as well: out there it is out of the players' sight and out of the server's activation range, so it would stand still and wait for a player who never comes.
- The **enemy counter is exact**: an enemy stops counting when it is really gone - killed by a player, killed by the admin, or removed by another plugin. An enemy that only waits in an unloaded chunk still counts, so a wave is never cleared by accident. A wave therefore only ends when the **last** enemy is really dead - it never clears itself.
- Enemies of a wave that runs longer than **5 minutes** start **glowing** (like a spectral arrow hit), so an enemy stuck in a cave can be found. The same glow can be switched on at any moment with **`/siege glow`** - no need to wait out the timer when the wave is nearly cleared. The forced glow lasts **only for the current wave** (the next one starts dark again) and the answer names the remaining enemies and their coordinates, so the last one can be hunted down. A wave that is not cleared aborts after **20 minutes** (`waves.max-wave-seconds`); at 5 minutes the remaining enemies start glowing, at 1 minute left the wave is announced again, and the last 10 seconds are counted down.
- A wave that drags on makes the mobs **desperate**: after ten minutes into the wave (`ai.desperate-after-seconds`) they dig through everything, **even stone with bare hands**. Nothing that is worth more than the siege (chests, furnaces, redstone, ores, metal blocks) is ever broken.

## Nether and End rules

The **same chosen enemy types** (zombies, skeletons, pillagers, vindicators, wither skeletons, piglin brutes, blazes, creepers, ravager) spawn in all dimensions. Starting a match in the Nether or End creates an arena *in that world*; no automatic replacement of the roster happens.

### The enemy roster

| Type | Unlocks | Notes |
|---|---|---|
| Zombie | wave 1 | the baseline melee enemy; carries an **axe, a sword or a pickaxe** (axe from wave 3) and breaches wooden walls |
| Skeleton | wave 1 | shoots arrows from a distance, with a **bow** |
| Pillager | wave 2 | throws javelins, with a **crossbow** |
| Vindicator | wave 3 | fast, closes the gap quickly; carries an **iron axe** and breaches wooden walls |
| Wither Skeleton | wave 4 | tougher skeleton, often with a **stone sword** |
| Piglin Brute | wave 5 | the heavy hitter: 50 HP in vanilla, scaled like every other mob, so it stays a tank; its **netherite axe** breaks wooden walls. It stays a brute in every dimension and never turns into a zombified piglin |
| Blaze | wave 6 | hovers and shoots fireballs (no terrain damage) |
| Creeper | wave 7 | explodes near a player; the explosion never damages the terrain, and the creeper dies in its own blast |
| Ravager | final wave | **off by default**, at most one per wave, mini-boss with the vanilla charge attack; its charge smashes simple blocks inside the destruction radius |

### How the mobs get inside

- **Players first**: the anchor marks where the fortress is and where the enemies spawn, but the mobs hunt the **players**. A mob picks the nearest player within `ai.detection-radius` and keeps that player as its target until the player dies, leaves the world or gets out of `ai.pursuit-radius`. A **visible** player is preferred, but a player the mob cannot see is taken as well (`ai.require-line-of-sight: false`), so hiding in a box is no protection - the mob digs the box open.
- **Doors**: every siege mob opens a closed wooden door, trapdoor or fence gate that stands between it and its goal - also for creepers, blazes and piglin brutes, which have no vanilla door AI. Both halves of a door are opened, otherwise tall mobs would still collide with the upper half. A trapdoor is a **single** block (its top half lies flat in the floor, its bottom half hangs from the ceiling), so it is opened on its own - that is how a mob gets through the trapdoor over a ladder hole. Iron and copper doors are redstone parts and stay closed; only a pickaxe breaks them. A door is only opened **inside the destruction radius** (`arena.destruction-radius`), the same zone that may be mined.
- **Walls**: a mob that stops making progress looks for a route and mines the first whitelisted block in the way. The block is journaled before it is removed, so it can be restored when the match ends. A mob that can already see the player still keeps mining: it is only "arrived" when it has a **line of sight** to the player, so a 1x1x2 box is opened rather than walked around. Once a mob is breaking its way in, it **keeps breaking** - it does not wait for another stall between two blocks. In the last quarter of the waves the mobs break through up to `mining.late-breach-speed` times faster (2 = twice as fast).
- **Ladders**: Paper's pathfinder treats a ladder as a wall, so the plugin steers the mobs itself: a mob that is stuck below its goal looks for a ladder within reach, climbs it and steps onto the platform at the top. The ladder has to lead up to the goal's level, and the mob never leaves the arena. Toggle with `ai.ladder-climbing`. The mob also opens a trapdoor **while it climbs**, so a hatch over the ladder hole does not stop it.
- **Gaps and trenches**: the ground in front of a mob is checked **every tick**, so a mob never walks into a hole. A one block wide and one block deep gap is **jumped** (`ai.jump-over-gaps`), a wider or deeper hole is **bridged**: the mob places a cobblestone floor across it and walks over. A hole that is two blocks deep is bridged as well - the mob would not get out of it otherwise. A trench around your base is therefore no longer a safe spot. A one block step is jumped right away, not after a delay.
- **Towers**: if the goal is more than 1.5 blocks above the mob, the mob **builds itself up**: it jumps and puts a block under its own feet, again and again, until it reaches the player's level. This is a late game ability - it starts at `ai.building-from-wave-percent` (75 % = the last quarter of the waves) **or** after `ai.building-after-seconds` (300 = five minutes into the wave, the same moment the enemies start glowing). A player on a roof is therefore reached in every wave - after five minutes the mobs place their own staircase up to him.
- **Progress** is measured as the distance to the goal, not as "did the position change": a mob that jitters against a wall is recognised as stuck and starts looking for a way in.
- **Desperate digging**: a wave that has been running for a long time makes the mobs dig through everything - **even stone with bare hands**. After `ai.desperate-after-seconds` (600 = ten minutes into the wave) the normal tool rules are relaxed: a zombie without a pickaxe tears a stone wall down instead of standing in front of it. The blocks that are worth more than the siege (chests, furnaces, redstone, ores, metal blocks, fluids) stay protected, and the iron and copper doors still need a pickaxe. The digging is slower than with the right tool (`ai.desperate-break-factor`), so the wall still takes a moment per block. Set `ai.desperate-after-seconds: 0` to switch it off, `-1` to let the mobs dig through everything from the first second of every wave.

Every block a mob places is **journaled** like a broken block, so the bridges and staircases the mobs build are removed again when the match ends. Placing only happens **inside the destruction radius**.

### Breaking blocks like a player

The mobs follow the vanilla tool rules, so the gear they carry matters:

| Block | Bare hands | Axe | Pickaxe | Shovel |
|---|---|---|---|---|
| dirt, sand, gravel | yes (slow) | yes (slow) | yes (slow) | yes (fast) |
| planks, logs, wood, leaves | yes (slow) | yes (fast) | yes (slow) | yes (slow) |
| cobblestone, stone, deepslate | no | no | yes (fast) | no |
| iron and copper doors | no | no | yes | no |
| chests, furnaces, redstone, ores, metal blocks | no | no | no | no |

The right tool is much faster than the wrong one. The block is journaled before it is removed, and the mining happens only **inside the destruction radius** (`arena.destruction-radius`).

**Desperate digging** relaxes the table: after `ai.desperate-after-seconds` (ten minutes into the wave by default) a mob digs through stone, cobblestone and deepslate with its **bare hands** - slowly, but it gets through. The protected blocks (chests, furnaces, redstone, ores, metal blocks, lava, water) and the redstone doors are never broken, not even in desperation.

### The gear the mobs carry

The melee mobs are equipped like players and **use** what they carry:

- **Weapons**: a sword or an axe in the main hand hits with the damage of that weapon (in addition to the wave scaling).
- **Tools**: the axe, pickaxe or shovel in the main hand decides how fast the mob breaks a block.
- **Shields**: a melee mob may carry a shield in the off hand. It raises the shield while it closes in on a player and lowers it again when it is close enough to strike - the mob really blocks the hits.
- **Bows and crossbows**: skeletons and pillagers keep theirs and shoot with them.

How often a mob gets a weapon, a tool or a shield is configurable (`mobs.random-equipment`, `mobs.equipment-chance`, `mobs.shield-chance`).

A wave is a mix: the unlocked types take turns, so a late wave contains several kinds of enemy at once. The types are enabled in `/siege setup` and in the `mobs:` section of `config.yml`; at least one type that already appears in wave 1 must be enabled, otherwise the match refuses to start.

Spawns are selected **close to the anchor** (20-55 blocks, never outside the movement radius), on its level (within 8 vertical blocks), on solid ground with two air blocks of clearance, and in the **same kind of environment**: a player on the surface gets surface spawns, a player in a cave gets cave spawns. Spots in the direction the player is looking are preferred, so a wave arrives where it is being watched. This avoids the Nether bedrock roof and End void, and does not create platforms or bridge empty space. Small End islands, disconnected islands, caves without safe space, and lava fields may offer too few spawn sites. The plugin will **abort the wave rather than spawn mobs into the void or silently award victory**.

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

### Running a local test server

`scripts/local-server.sh` sets up a disposable Paper server for local testing:

```sh
scripts/local-server.sh                     # pick a Paper version from a menu
scripts/local-server.sh --version 26.2      # or name one directly
scripts/local-server.sh --list              # list the available versions
scripts/local-server.sh --version 26.2 --rebuild --restart   # rebuild the plugin and restart the server
```

It downloads Paper, builds the plugin against the matching Paper API and starts the server, then you join on `localhost:25565` (offline mode, so any player name works) and run `/siege anchor`, `/siege setup`, `/siege start`.

### The server console

The server runs inside a detached **screen** session (`mss-paper`), so the console stays reachable from any terminal — the script itself can exit without killing the server:

| Command | Effect |
|---|---|
| `scripts/console.sh` | attach to the console; detach again with `Ctrl+A D` |
| `scripts/local-server.sh --attach` | same, through the main script |
| `scripts/console.sh siege status` | run a single console command and show the log tail |
| `tail -f local-server/server/logs/latest.log` | the clean server log (no terminal escape codes) |
| `screen -ls` | list sessions, `screen -r mss-paper` attaches directly |

Without `screen` installed the server keeps running in the terminal that started it.

If the plugin has not been built yet for the selected version, the script offers to build it and shows the exact `minecraftVersion`/`paperApiVersion` it will use. With an existing build it asks whether to rebuild; answering no keeps the current JAR.

Everything lives in `local-server/`, which is **git-ignored**:

| Path | Contents |
|---|---|
| `local-server/tools` | cached JDK 25 and Gradle distributions |
| `local-server/paper` | cached Paper server JARs (one folder per Minecraft version) |
| `local-server/server` | the running server: world, plugins, logs |

`local-server/paper/<version>/` is only the download cache. The server itself runs from `local-server/server/`, and `local-server/server/plugins/MSS-*.jar` is a **symlink into `build/libs/`**, so the server always runs the JAR Gradle just produced — rebuild and restart, no copy step.

Downloads are cached, so a second run of the same Paper version only rebuilds the plugin. The script also downloads a JDK and Gradle when the machine has no suitable ones, so a fresh clone needs nothing but `bash`, `curl`, `tar`, `unzip` and `python3`.

Options: `--version <mc>`, `--plugin-version <v>`, `--server-port <n>`, `--no-build`, `--rebuild`, `--clean`, `--no-start`, `--reset-world`, `--restart`, `--attach`, `--list`, `--help`.

`--rebuild` forces a clean Gradle build without asking; `--restart` stops a server previously started by this script and starts it again. Together they are the "rebuild the plugin and restart" loop.

Bukkit writes `plugins/SiegeSurvival/config.yml` once and never refreshes it, so plugin updates can ship defaults the running server never sees. The script therefore merges **new** default keys into the live config (existing values are kept) and reports values that differ from the packaged defaults, offering to reset the file.

The plugin requires Paper 26.1+, so older Paper versions can be started but the plugin will not load on them.

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

## Configuration

`plugins/SiegeSurvival/config.yml` holds every knob; the packaged file carries a short comment per key. Bukkit writes the file once and never refreshes it, so a plugin update can ship defaults the running server never sees - `scripts/sync-config.py` merges the missing keys into a live config.

| Key | Default | What it does |
|---|---|---|
| `permissions.require-admin` | `true` | only players with `siege.admin` may control a match |
| `game.default-waves` | `10` | wave count of `/siege start` |
| `game.max-waves` | `100` | upper limit for `/siege start <waves>` |
| `game.preparation-minutes` | `10` | length of `/siege start prep` |
| `game.intermission-seconds` | `10` | pause between two waves |
| `game.countdown-seconds` | `5` | on-screen countdown before a wave starts |
| `game.status-interval-seconds` | `0` | chat summary of the counters; `0` = off (the action bar always shows them) |
| `game.reconnect-grace-seconds` | `60` | how long a disconnected player is still counted as alive |
| `arena.movement-radius` | `60` | player boundary around the anchor, active from wave 1 |
| `arena.combat-radius` | `80` | how far mobs may engage; also the maximum for `/siege radius` |
| `arena.destruction-radius` | `60` | the only area where mobs may mine |
| `arena.spawn-min-distance` | `20` | closest spawn distance to the anchor |
| `arena.spawn-max-distance` | `55` | farthest spawn distance (clamped to the movement radius) |
| `arena.spawn-vertical-range` | `8` | how far above/below the anchor level enemies may spawn |
| `arena.natural-spawns` | `false` | keep vanilla hostile spawning inside the arena |
| `arena.rollback-mob-damage` | `true` | restore journaled mob-mined blocks on stop/restart |
| `waves.base-enemies` | `5` | enemies in wave 1 (before the player multiplier) |
| `waves.growth-per-wave` | `1.3` | enemies added per wave |
| `waves.max-enemies-per-wave` | `120` | cap of a single wave |
| `waves.max-active-mobs` | `40` | how many enemies may be alive at once |
| `waves.max-wave-seconds` | `1200` | a wave that is not cleared aborts after 20 minutes |
| `waves.glow-after-seconds` | `300` | after 5 minutes the remaining enemies start glowing |
| `waves.spawn-batch-size` | `5` | enemies spawned per second |
| `mobs.<type>` | see below | which roster types may spawn; the same list as the enemy menu |
| `ai.detection-radius` | `24` | how far a mob notices a player |
| `ai.pursuit-radius` | `32` | how far a mob keeps chasing its target |
| `ai.path-speed` | `1.25` | movement speed multiplier while advancing on the anchor |
| `ai.lost-target-seconds` | `6` | how long a mob sticks to a player it lost sight of |
| `ai.door-opening` | `true` | mobs open the doors, trapdoors and fence gates they walk into |
| `ai.ladder-climbing` | `true` | mobs climb a ladder to reach an anchor that is above them |
| `ai.require-line-of-sight` | `false` | `false` = a mob also hunts a player it cannot see, so a box is no safe spot |
| `ai.jump-over-gaps` | `true` | mobs jump a one block gap and a one block step |
| `ai.building-enabled` | `true` | mobs may place blocks (bridges and staircases) |
| `ai.building-from-wave-percent` | `75` | from which wave (in percent) the mobs build themselves up; 75 = the last quarter |
| `ai.building-after-seconds` | `300` | after this many seconds in a wave the mobs build themselves up as well (the same moment the enemies start glowing); `0` = only in the last quarter |
| `ai.bridging-from-wave-percent` | `0` | from which wave (in percent) the mobs bridge a gap; 0 = from the first wave |
| `ai.debug-logging` | `false` | logs what every mob is doing (goal, distance, stuck, mining, terrain) once per second |
| `ai.desperate-after-seconds` | `600` | after this many seconds in a wave a mob digs through anything, even stone with bare hands; `0` = never, `-1` = always |
| `ai.desperate-break-factor` | `2.0` | how much slower a desperate break with bare hands is |
| `mobs.random-equipment` | `true` | mobs carry weapons and tools and really use them |
| `mobs.equipment-chance` | `0.45` | how often a melee mob gets a weapon or a tool |
| `mobs.shield-chance` | `0.25` | how often a melee mob carries a shield it raises while fighting |
| `mining.enabled` | `true` | mobs may breach whitelisted blocks (**modifies your world**) |
| `mining.max-concurrent-miners` | `8` | how many mobs may mine at the same time |
| `mining.deny-if-protection-plugin-present` | `true` | refuse to mine at all when a claim plugin is installed |
| `mining.ravager-smashes-blocks` | `true` | a charging ravager smashes simple blocks inside the destruction radius |
| `mining.late-breach-speed` | `2.0` | how much faster the mobs break walls in the last quarter of the waves |

The `mobs:` keys mirror the roster: `zombie`, `skeleton`, `pillager`, `vindicator`, `wither_skeleton`, `piglin_brute`, `blaze` and `creeper` default to `true`, `ravager` to `false`.

## Commands

### While playing

| Command | Parameter | What it does |
|---|---|---|
| `/siege help` | – | lists the subcommands |
| `/siege status` | – | shows the state (idle / preparing / wave / intermission), the current wave, the living participants, the remaining enemies, the **type mix of the running wave**, the mining state (`on`, `on (desperate)` while the mobs dig through stone), whether the glow is forced (`forced (this wave)` or `auto`) and - while enemies are left - **where they are**. Visible to everyone. |
| `/siege setup` | – | opens the enemy menu; click a mob to enable or disable that enemy type. The tooltip shows the wave in which it unlocks. The selection applies to the next match; `config.yml` holds the defaults. |
| `/siege anchor` | – | saves the block **under your feet** as the anchor: the centre of the arena, where the enemies spawn and fall back to when no player is around, and where players are returned to. You must stand on solid ground (Overworld, Nether or End). |
| `/siege start` | – | starts the match immediately, using the saved anchor (or your current position if none is set) and the default wave count (**10**). Everyone online joins at your location. |
| `/siege start <waves>` | `waves` – how many waves to play | number of waves for this match (1 – `game.max-waves`, 100 by default). |
| `/siege start prep` | – | starts with the default preparation time (`game.preparation-minutes`, 10 minutes) and the default wave count. |
| `/siege start prep <minutes>` | `minutes` – preparation length | how long the players may prepare. Movement is **unrestricted** until wave 1; then everyone is returned to the anchor. |
| `/siege start prep <minutes> <waves>` | `minutes`, `waves` | both of the above. |
| `/siege radius <blocks>` | `blocks` – radius in blocks | movement radius for the **next** match (25 – `arena.combat-radius`, 80 by default). Players may not leave it from wave 1 on; enemies spawn inside it and may only mine inside it. |
| `/siege glow [on\|off]` | `on`/`off` – optional | makes every living siege mob **glow** (visible through walls) so the last enemies can be found; without an argument the glow is toggled. Also works from the server console. The forced glow lasts **only for the current wave**: the next wave starts dark and glows on its own after five minutes (`waves.glow-after-seconds`) or when `/siege glow` is used again. The answer names the remaining enemies and their coordinates, and holds the chunks of mobs that left the loaded area, so a hiding mob comes back and can be hunted down. `/siege status` shows `Glow forced` or `Glow auto` and the position of the remaining enemies. |
| `/siege stop` | – | ends the match immediately: mobs are removed, tasks are cancelled, player game modes and locations are restored. |

The order of the parameters matters: `prep` must come first, then the minutes, then the waves (`/siege start prep 5 3` = 5 minutes of preparation, 3 waves).

By default, controlling a match requires `siege.admin` (OPs have it by default), configurable via `permissions.require-admin`. Status is visible to players, and `stop` also works from the server console.

## Match lifecycle

`IDLE → PREPARING (optional) → WAVE → INTERMISSION → WAVE … → VICTORY/DEFEAT → IDLE`.

Movement boundary is **off** before the first wave, including throughout preparation. At the first wave players outside the radius are teleported to the anchor; teleportation and vehicle travel are also restricted during active play. This does not change the world's global border. A player who dies is eliminated for the rest of the match and continues as a spectator; a player who joins during the match is put into spectator mode and kept out of the arena until the next round.

## Safety and limitations

- One match per server. Match participants are the online players at the start. The plugin does not create an isolated world.
- The chunks of the arena (the circle around the anchor, up to `arena.combat-radius`) are **held loaded** during a match, so a mob that wanders off does not freeze in an unloaded chunk. The plugin adds the ticket itself and releases it when the match ends.
- A match can change the **existing survival world**. The radius restricts where the plugin itself breaks blocks; it is not an anti-grief plugin for ordinary players.
- Mining supports a bounded *same-height* wall-breaching planner and Paper's block destroy speed for tool-based timing. Complex 3D mazes remain experimental. Mining is **enabled by default** (`mining.enabled: true`) for the intended gameplay, but modifies your existing survival world; disable it for protected/irreplaceable bases and always back up first.
- Mob-mined whitelisted blocks are recorded before removal and restored at cleanup/restart when still air (enabled by `arena.rollback-mob-damage`). Player edits, gravity, flowing liquids and other updates cannot be reliably reversed. Do not run an active siege on an irreplaceable base.
- The mobs also **place** blocks (bridges over trenches, staircases next to a tower). Those blocks are journaled as well and are removed again when the match ends, as long as they are still the same block. A player who builds over them first keeps their version. A block is never placed in mid-air, in a cell that a player occupies, or outside the destruction radius.
- Vanilla block breaking by a siege mob is mostly blocked: a creeper's explosion only hurts players, never the terrain. The exception is the **ravager charge**, which smashes simple blocks (no chests, furnaces, redstone or lava) inside the destruction radius; those blocks are journaled as well. Everything else that changes terrain is the plugin's own mining (inside the destruction radius, journaled).
- **Doors that the siege opens stay open** after the match. Opening a door only flips its block data, it is not journalled and not restored. Close them yourself if you want your house sealed again.
- No creative mode during a match; original player game modes and locations are restored on normal cleanup.
- Original game modes and locations are journaled to a server-local recovery file. They are restored on join or post-respawn after interruptions; terrain and inventory are not rolled back.
- Region-claim integrations are not yet implemented. By default, mining refuses to modify blocks at all when known protection plugins (WorldGuard, GriefPrevention, Lands, Towny, Residence, GriefDefender, ClaimChunk) are installed - that includes the ravager charge. This is fail-closed, not full integration. The plugin logs a warning at startup naming the detected claim plugin, `/siege status` shows `Mining off (<Plugin> detected)`, and `mining.deny-if-protection-plugin-present: false` is the opt-out if you accept that siege mobs breach protected blocks. Doors inside the destruction radius can still be opened inside a claim - that is a block data change, not a break.
- Servers running on **Peaceful** are not supported for sieges. Difficulty isn't forcibly changed globally.
- To test concurrency or difficult structures, use a separate Paper test server.

## Development

See [CLAUDE.md](CLAUDE.md) (also exposed via `AGENTS.md`) for coding-agent guidance, feature boundaries and acceptance criteria. Small, testable changes are preferred; the AI/mining controller is the highest-risk component.

### CI

GitHub Actions builds against pinned Paper 26.1 and 26.2 APIs on JDK 25, runs unit tests, and includes a Paper 26.2 server smoke test. Real multiplayer, Nether/End pathfinding, and world-damage recovery still require dedicated live playtesting.

## License

[Unlicense](LICENSE). Public-domain dedication as provided by the repository owner.
