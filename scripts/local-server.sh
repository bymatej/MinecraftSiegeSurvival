#!/usr/bin/env bash
#
# Local Paper test server for MinecraftSiegeSurvival.
#
# Downloads Paper, builds the plugin against the matching Paper API and starts
# a disposable server in ./local-server (git-ignored). Everything the script
# needs (JDK, Gradle, Paper) is cached under local-server/tools and
# local-server/paper, so re-runs are fast and never touch the repository
# working tree.
#
# Usage:
#   scripts/local-server.sh                    # interactive version picker
#   scripts/local-server.sh --version 26.2     # non-interactive
#   scripts/local-server.sh --list             # list available versions
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

LOCAL_DIR="$REPO_ROOT/local-server"
TOOLS_DIR="$LOCAL_DIR/tools"
PAPER_CACHE_DIR="$LOCAL_DIR/paper"
SERVER_DIR="$LOCAL_DIR/server"
PLUGINS_DIR="$SERVER_DIR/plugins"

PAPER_API_BASE="https://fill.papermc.io/v3"
PAPER_MAVEN_META="https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml"
ADOPTIUM_API="https://api.adoptium.net/v3"
USER_AGENT="MinecraftSiegeSurvival-local-server/1.0 (local development script)"

GRADLE_VERSION="${GRADLE_VERSION:-9.2.1}"
JDK_MAJOR="${JDK_MAJOR:-25}"
GRADLE_MIN_MAJOR=9

SERVER_PORT=25565
PLUGIN_VERSION=""
MC_VERSION=""
DO_BUILD=1
DO_START=1
RESET_WORLD=0
CLEAN_BUILD=0
DO_RESTART=0
FORCE_REBUILD=0
ATTACH_ONLY=0
SESSION_NAME="mss-paper"

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi

info() { printf '%s==>%s %s\n' "$C_BLUE$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$C_GREEN$C_BOLD" "$C_RESET" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_YELLOW$C_BOLD" "$C_RESET" "$*" >&2; }
die()  { printf '%serror%s %s\n' "$C_RED$C_BOLD" "$C_RESET" "$*" >&2; exit 1; }
hint() { printf '     %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }

confirm() {
  # confirm <question> <y|n default> - reads from the terminal, never blocks
  # when stdin is not interactive.
  local question="$1" default="${2:-y}" reply
  if [[ ! -t 0 ]]; then
    [[ "$default" == "y" ]]
    return $?
  fi
  if [[ "$default" == "y" ]]; then
    printf '%s [Y/n] ' "$question" >&2
  else
    printf '%s [y/N] ' "$question" >&2
  fi
  read -r reply || reply=""
  reply="${reply:-$default}"
  [[ "$reply" =~ ^[Yy] ]]
}

port_in_use() {
  # True when something is already listening on the given TCP port.
  local port="$1"
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || return 1
  exec 3<&- 3>&-
  return 0
}

find_server_pid() {
  # Find the Paper process running from the server directory. Used as a
  # fallback for servers started before the pid file existed.
  local proc pid cwd
  for proc in /proc/[0-9]*; do
    pid="${proc#/proc/}"
    cwd="$(readlink -f "$proc/cwd" 2>/dev/null || true)"
    [[ "$cwd" == "$SERVER_DIR" ]] || continue
    if grep -qa "paper.jar" "$proc/cmdline" 2>/dev/null; then
      printf '%s' "$pid"
      return 0
    fi
  done
  return 1
}

stop_running_server() {
  # Stop the server this script started earlier (tracked in server.pid).
  # Returns 0 when a server was stopped, 1 when none was running.
  local pid_file="$SERVER_DIR/server.pid" pid waited
  pid=""
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if [[ ! "$pid" =~ ^[0-9]+$ ]] || ! kill -0 "$pid" 2>/dev/null; then
      pid=""
    fi
  fi
  if [[ -z "$pid" ]]; then
    pid="$(find_server_pid || true)"
  fi
  if [[ -z "$pid" ]]; then
    return 1
  fi
  info "Stopping the running server (pid $pid)"
  kill -TERM "$pid" 2>/dev/null || true
  for waited in $(seq 1 60); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      ok "Server stopped"
      return 0
    fi
    sleep 1
  done
  warn "The server (pid $pid) did not stop within 60 seconds"
  return 1
}

# The console lives in a detached screen session, so the server can be
# started from a script while the commands stay reachable:
#   screen -r "$SESSION_NAME"   attach, Ctrl+A D detaches again
screen_session_exists() {
  command -v screen >/dev/null 2>&1 && screen -ls | grep -q "\.${SESSION_NAME}[[:space:]]"
}

stop_screen_session() {
  screen_session_exists || return 1
  info "Closing the console session '$SESSION_NAME'"
  screen -S "$SESSION_NAME" -X quit >/dev/null 2>&1 || true
  return 0
}

usage() {
  cat <<'EOF'
Local Paper test server for Minecraft Siege Survival.

Usage:
  scripts/local-server.sh [options]

Options:
  -v, --version <mc>       Paper/Minecraft version to run (skips the menu),
                           e.g. 26.2, 26.1.2, 1.21.11
  -p, --plugin-version <v> Plugin version embedded in plugin.yml
                           (default: the build script's own default)
  -P, --server-port <n>    Server port (default: 25565)
  -l, --list               List the available Paper versions and exit
  -b, --no-build           Reuse the already built plugin JAR
  -f, --rebuild            Rebuild the plugin without asking (implies --clean)
  -c, --clean              Run a clean Gradle build instead of an incremental one
  -s, --no-start           Prepare the server directory but do not start it
  -r, --reset-world        Delete the existing world before starting
  -R, --restart            Stop the server started by this script, then start it again
  -a, --attach             Attach to the running server console (screen), then detach
  -h, --help               Show this help

Everything is downloaded into ./local-server (git-ignored):
  local-server/tools      cached JDK and Gradle distributions
  local-server/paper      cached Paper server JARs
  local-server/server     the running server (world, plugins, logs)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--version)       MC_VERSION="${2:?--version needs a value}"; shift 2 ;;
    -p|--plugin-version) PLUGIN_VERSION="${2:?--plugin-version needs a value}"; shift 2 ;;
    -P|--server-port)   SERVER_PORT="${2:?--server-port needs a value}"; shift 2 ;;
    -l|--list)          LIST_ONLY=1; shift ;;
    -b|--no-build)      DO_BUILD=0; shift ;;
    -f|--rebuild)      FORCE_REBUILD=1; CLEAN_BUILD=1; shift ;;
    -s|--no-start)      DO_START=0; shift ;;
    -r|--reset-world)   RESET_WORLD=1; shift ;;
    -R|--restart)       DO_RESTART=1; shift ;;
    -a|--attach)        ATTACH_ONLY=1; shift ;;
    -c|--clean)         CLEAN_BUILD=1; shift ;;
    -h|--help)          usage; exit 0 ;;
    *)                  die "Unknown option: $1 (try --help)" ;;
  esac
done

for tool in curl tar unzip; do
  command -v "$tool" >/dev/null 2>&1 || die "Required tool not found: $tool"
done
command -v python3 >/dev/null 2>&1 || die "Required tool not found: python3"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

fetch() {
  # fetch <url> [curl args...] -> stdout
  curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
    -H "User-Agent: $USER_AGENT" "$@"
}

fetch_to_file() {
  # fetch_to_file <url> <dest>
  local url="$1" dest="$2" tmp
  tmp="$(mktemp "${dest}.part.XXXXXX")"
  if [[ -t 2 ]]; then
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar \
      -H "User-Agent: $USER_AGENT" -o "$tmp" "$url"
  else
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
      -H "User-Agent: $USER_AGENT" -o "$tmp" "$url"
  fi
  mv -f "$tmp" "$dest"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# ---------------------------------------------------------------------------
# JDK / Gradle / Paper discovery
# ---------------------------------------------------------------------------

jdk_major_of() {
  # jdk_major_of <javac path> -> major version number, or empty
  local javac="$1" raw
  [[ -x "$javac" ]] || return 1
  raw="$("$javac" -version 2>&1 | head -1)" || return 1
  raw="${raw#javac }"
  raw="${raw%%.*}"
  [[ "$raw" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$raw"
}

find_local_jdk() {
  # Print the path of a usable JDK (has javac, major >= $JDK_MAJOR), if any.
  local candidate javac major
  local roots=()
  [[ -n "${JAVA_HOME:-}" ]] && roots+=("$JAVA_HOME")
  roots+=("$TOOLS_DIR"/jdk-*)
  roots+=(/usr/lib/jvm/* /opt/java/* /opt/jdk* "$HOME"/.gradle/jdks/* "$HOME"/.jdks/*)
  for candidate in "${roots[@]}"; do
    [[ -d "$candidate" ]] || continue
    javac="$candidate/bin/javac"
    major="$(jdk_major_of "$javac" || true)"
    if [[ -n "$major" ]] && (( major >= JDK_MAJOR )); then
      printf '%s' "$candidate"
      return 0
    fi
  done
  # javac straight on PATH
  if command -v javac >/dev/null 2>&1; then
    javac="$(command -v javac)"
    major="$(jdk_major_of "$javac" || true)"
    if [[ -n "$major" ]] && (( major >= JDK_MAJOR )); then
      printf '%s' "$(cd -- "$(dirname -- "$javac")/.." && pwd)"
      return 0
    fi
  fi
  return 1
}

download_jdk() {
  # Download a Temurin JDK into the local tools cache and print its home.
  local arch url dest extract_root
  case "$(uname -m)" in
    x86_64|amd64) arch="x64" ;;
    aarch64|arm64) arch="aarch64" ;;
    *) die "Unsupported CPU architecture for the JDK download: $(uname -m)" ;;
  esac
  dest="$TOOLS_DIR/jdk-$JDK_MAJOR-$arch"
  if [[ -x "$dest/bin/javac" ]]; then
    info "Using the cached JDK $JDK_MAJOR ($arch)" >&2
    printf '%s' "$dest"
    return 0
  fi
  mkdir -p "$dest"
  url="$ADOPTIUM_API/binary/latest/$JDK_MAJOR/ga/linux/$arch/jdk/hotspot/normal/eclipse"
  info "Downloading Temurin JDK $JDK_MAJOR ($arch) - one-time, cached in local-server/tools" >&2
  local archive="$dest/jdk.tar.gz"
  fetch_to_file "$url" "$archive"
  tar -xzf "$archive" -C "$dest" --strip-components=1
  rm -f "$archive"
  [[ -x "$dest/bin/javac" ]] || die "Downloaded JDK does not contain bin/javac"
  printf '%s' "$dest"
}

ensure_jdk() {
  # Prints the JDK home on stdout; all messages go to stderr so the caller can
  # capture the path.
  local jdk
  if jdk="$(find_local_jdk)"; then
    ok "JDK $(jdk_major_of "$jdk/bin/javac") at $jdk" >&2
  else
    jdk="$(download_jdk)"
    ok "JDK $(jdk_major_of "$jdk/bin/javac") downloaded to $jdk" >&2
  fi
  printf '%s' "$jdk"
}

ensure_gradle() {
  # Prints the gradle executable on stdout; messages go to stderr.
  local wrapper="$REPO_ROOT/gradlew" dist
  if [[ -x "$wrapper" ]]; then
    ok "Using the Gradle wrapper" >&2
    printf '%s' "$wrapper"
    return 0
  fi
  if command -v gradle >/dev/null 2>&1; then
    local raw major
    raw="$(gradle --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')"
    major="${raw%%.*}"
    if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= GRADLE_MIN_MAJOR )); then
      ok "Using Gradle $raw from PATH" >&2
      printf 'gradle'
      return 0
    fi
    warn "Gradle $raw on PATH is too old (need >= $GRADLE_MIN_MAJOR); using the cached $GRADLE_VERSION instead" >&2
  fi
  dist="$TOOLS_DIR/gradle-$GRADLE_VERSION"
  if [[ -x "$dist/gradle-$GRADLE_VERSION/bin/gradle" ]]; then
    ok "Using cached Gradle $GRADLE_VERSION" >&2
  else
    info "Downloading Gradle $GRADLE_VERSION - one-time, cached in local-server/tools" >&2
    mkdir -p "$dist"
    fetch_to_file "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
      "$dist/gradle.zip"
    unzip -q -o "$dist/gradle.zip" -d "$dist"
    rm -f "$dist/gradle.zip"
    [[ -x "$dist/gradle-$GRADLE_VERSION/bin/gradle" ]] || die "Gradle download failed"
    ok "Gradle $GRADLE_VERSION ready" >&2
  fi
  printf '%s' "$dist/gradle-$GRADLE_VERSION/bin/gradle"
}

# ---------------------------------------------------------------------------
# Paper version handling
# ---------------------------------------------------------------------------

paper_versions_json() {
  fetch "$PAPER_API_BASE/projects/paper"
}

parse_stable_versions() {
  # Print up to 8 stable Paper versions, newest first.
  python3 -I -c '
import json, re, sys
data = json.load(sys.stdin)
versions = []
for family in data.get("versions", {}):
    versions.extend(data["versions"][family])
def key(v):
    out = []
    for part in re.split(r"[.\-_+]", v):
        out.append((0, int(part)) if part.isdigit() else (1, part))
    return out
stable = [v for v in versions
          if not re.search(r"-(rc|pre|alpha|beta|snapshot)", v, re.I)]
stable.sort(key=key, reverse=True)
for v in stable[:8]:
    print(v)
'
}

resolve_build() {
  # Resolve the newest stable build of a Paper version.
  # Prints: "id<TAB>name<TAB>size<TAB>sha256<TAB>url"
  local mc="$1" builds
  builds="$(fetch "$PAPER_API_BASE/projects/paper/versions/$mc/builds")" \
    || die "No Paper builds published for version '$mc'"
  printf '%s' "$builds" | python3 -I -c '
import json, sys
builds = json.load(sys.stdin)
stable = [b for b in builds if b.get("channel") == "STABLE"] or builds
if not stable:
    raise SystemExit("no builds")
stable.sort(key=lambda b: b.get("id", 0))
b = stable[-1]
d = b["downloads"]["server:default"]
print("\t".join([
    str(b.get("id", 0)),
    d.get("name", f"paper-{sys.argv[1]}.jar"),
    str(d.get("size", 0)),
    d.get("checksums", {}).get("sha256", ""),
    d["url"],
]))
' "$mc"
}

resolve_paper_api_version() {
  # Resolve the paper-api artifact version matching a Minecraft version and,
  # when given, the Paper build number we are going to run.
  local mc="$1" max_build="${2:-}" meta
  meta="$(fetch "$PAPER_MAVEN_META")" || die "Could not read the paper-api Maven metadata"
  printf '%s' "$meta" | python3 -I -c '
import re, sys
mc = sys.argv[1]
max_build = int(sys.argv[2]) if sys.argv[2] else None
versions = re.findall(r"<version>([^<]+)</version>", sys.stdin.read())
candidates = []
for v in versions:
    m = re.fullmatch(re.escape(mc) + r"\.build\.(\d+)-stable", v)
    if m:
        candidates.append((int(m.group(1)), v))
        continue
    if re.fullmatch(re.escape(mc) + r"-R0\.1-SNAPSHOT", v):
        candidates.append((0, v))
if not candidates:
    print("")
    raise SystemExit(0)
candidates.sort()
if max_build is not None:
    eligible = [c for c in candidates if c[1].endswith("-stable") and c[0] <= max_build]
    if eligible:
        print(eligible[-1][1])
        raise SystemExit(0)
print(candidates[-1][1])
' "$mc" "$max_build"
}

api_version_for() {
  # The plugin's api-version is the major.minor family (26.1.2 -> 26.1).
  local mc="$1" first second
  first="${mc%%.*}"
  if [[ "$mc" == *.* ]]; then
    second="${mc#*.}"
    second="${second%%.*}"
    printf '%s.%s' "$first" "$second"
  else
    printf '%s' "$mc"
  fi
}

choose_version() {
  # Interactive picker over the newest stable Paper versions.
  # The menu goes to stderr, only the selection is printed on stdout.
  local versions=() selected
  mapfile -t versions < <(paper_versions_json | parse_stable_versions)
  if [[ ${#versions[@]} -eq 0 ]]; then
    die "Could not read the list of Paper versions"
  fi
  printf '%sSelect the Paper version to run locally:%s\n' "$C_BOLD" "$C_RESET" >&2
  local i=1 v
  for v in "${versions[@]}"; do
    printf '  %d) %s\n' "$i" "$v" >&2
    i=$((i + 1))
  done
  printf '  %d) Custom version (type it)\n' "$i" >&2
  printf 'Choice [%s-%s]: ' "1" "$i" >&2
  read -r selected
  selected="${selected:-1}"
  if [[ ! "$selected" =~ ^[0-9]+$ ]] || (( selected < 1 || selected > i )); then
    die "Invalid selection: $selected"
  fi
  if (( selected == i )); then
    printf 'Custom Minecraft version: ' >&2
    read -r selected
    [[ -n "$selected" ]] || die "No version entered"
    printf '%s' "$selected"
    return 0
  fi
  printf '%s' "${versions[$((selected - 1))]}"
}

# ---------------------------------------------------------------------------
# Plugin build
# ---------------------------------------------------------------------------

build_plugin() {
  local gradle_bin="$1" jdk="$2" mc="$3" api_version="$4" plugin_version="$5"
  local -a args=(
    -p "$REPO_ROOT" build
    "-PminecraftVersion=$mc"
    "-PpaperApiVersion=$api_version"
  )
  if [[ -n "$plugin_version" ]]; then
    args+=("-PpluginVersion=$plugin_version")
  fi
  if (( CLEAN_BUILD )); then
    args=(clean "${args[@]}")
  fi
  info "Building the plugin (minecraftVersion=$mc, paperApiVersion=$api_version)"
  JAVA_HOME="$jdk" "$gradle_bin" "${args[@]}" || die "Gradle build failed"
}

find_plugin_jar() {
  local mc="$1" plugin_version="$2" jar
  if [[ -n "$plugin_version" ]]; then
    # An explicitly requested plugin version must match exactly.
    jar="$REPO_ROOT/build/libs/MSS-$mc.$plugin_version.jar"
    [[ -f "$jar" ]] && { printf '%s' "$jar"; return 0; }
    return 1
  fi
  jar="$(ls -1t "$REPO_ROOT"/build/libs/MSS-"$mc".*.jar 2>/dev/null | head -1 || true)"
  [[ -n "$jar" && -f "$jar" ]] && { printf '%s' "$jar"; return 0; }
  return 1
}

# ---------------------------------------------------------------------------
# Server directory
# ---------------------------------------------------------------------------

prepare_server_dir() {
  local mc="$1" paper_jar="$2" plugin_jar="$3"
  mkdir -p "$PLUGINS_DIR"

  # Symlink the plugin instead of copying it, so the server always runs the
  # JAR that Gradle just produced in build/libs.
  local target_name relative_target existing
  target_name="$(basename "$plugin_jar")"
  relative_target="$(python3 -I -c '
import os, sys
print(os.path.relpath(sys.argv[1], sys.argv[2]))
' "$plugin_jar" "$PLUGINS_DIR")"

  # Drop stale plugin JARs, they would double-register the plugin.
  for existing in "$PLUGINS_DIR"/MSS-*.jar; do
    [[ -e "$existing" || -L "$existing" ]] || continue
    [[ "$(basename "$existing")" == "$target_name" ]] && continue
    rm -f "$existing"
  done
  ln -sfn "$relative_target" "$PLUGINS_DIR/$target_name"
  ok "Plugin linked: plugins/$target_name -> $relative_target"

  ln -sfn "$paper_jar" "$SERVER_DIR/paper.jar"
  printf '%s\n' "$mc" > "$SERVER_DIR/.paper-version"

  if [[ ! -f "$SERVER_DIR/eula.txt" ]]; then
    cat > "$SERVER_DIR/eula.txt" <<'EOF'
# Written by scripts/local-server.sh.
# By running this server you accept the Minecraft EULA: https://aka.ms/MinecraftEULA
eula=true
EOF
    ok "eula.txt written (EULA accepted on your behalf: https://aka.ms/MinecraftEULA)"
  fi

  if [[ ! -f "$SERVER_DIR/server.properties" ]]; then
    cat > "$SERVER_DIR/server.properties" <<EOF
# Written by scripts/local-server.sh - disposable local test server.
motd=MinecraftSiegeSurvival local test server
server-port=$SERVER_PORT
online-mode=false
level-name=world
level-type=minecraft:normal
gamemode=survival
difficulty=normal
spawn-protection=0
max-players=20
view-distance=8
simulation-distance=8
allow-nether=true
enable-command-block=false
sync-chunk-writes=false
white-list=false
enforce-secure-profile=false
EOF
    ok "server.properties written (offline mode, normal difficulty)"
  elif ! grep -qx "server-port=$SERVER_PORT" "$SERVER_DIR/server.properties"; then
    # Keep hand edits, but honour --server-port.
    sed -i "s/^server-port=.*/server-port=$SERVER_PORT/" "$SERVER_DIR/server.properties"
    ok "server-port set to $SERVER_PORT"
  fi

  sync_plugin_config
}

# Bukkit writes the plugin config once and never refreshes it, so a plugin
# update can ship defaults a running server never sees. Add the new keys to
# the live config and report the values that still differ from the defaults.
sync_plugin_config() {
  local default_config="$REPO_ROOT/src/main/resources/config.yml"
  local live_config="$PLUGINS_DIR/SiegeSurvival/config.yml"
  [[ -f "$default_config" ]] || return 0

  if [[ ! -f "$live_config" ]]; then
    mkdir -p "$(dirname "$live_config")"
    cp "$default_config" "$live_config"
    ok "Plugin config written: plugins/SiegeSurvival/config.yml"
    return 0
  fi

  local report line differing=0
  report="$(python3 -I "$REPO_ROOT/scripts/sync-config.py" "$default_config" "$live_config" 2>/dev/null || true)"
  while IFS= read -r line; do
    case "$line" in
      "added "*) hint "config: ${line#added } added to the live config" ;;
      "differs "*) differing=$((differing + 1)) ;;
    esac
  done <<< "$report"

  if (( differing > 0 )); then
    warn "$differing config value(s) differ from the packaged defaults:"
    while IFS= read -r line; do
      [[ "$line" == differs\ * ]] && printf '    %s\n' "${line#differs }" >&2
    done <<< "$report"
    if confirm "Reset plugins/SiegeSurvival/config.yml to the packaged defaults?" n; then
      cp "$default_config" "$live_config"
      ok "Plugin config reset to the packaged defaults"
    else
      hint "keeping the current values (edit $live_config to change them)"
    fi
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
  if [[ "${LIST_ONLY:-0}" == "1" ]]; then
    paper_versions_json | parse_stable_versions
    exit 0
  fi

  if (( ATTACH_ONLY )); then
    screen_session_exists || die "No console session '$SESSION_NAME' is running."
    info "Attaching to the console session '$SESSION_NAME' (detach with Ctrl+A D)"
    screen -r "$SESSION_NAME"
    exit 0
  fi

  mkdir -p "$TOOLS_DIR" "$PAPER_CACHE_DIR" "$SERVER_DIR"

  local jdk gradle_bin
  info "Checking local toolchain"
  jdk="$(ensure_jdk)"
  gradle_bin="$(ensure_gradle)"

  if [[ -z "$MC_VERSION" ]]; then
    printf '\n'
    MC_VERSION="$(choose_version)"
  fi
  printf '\n'
  info "Selected Paper version: $MC_VERSION"

  local build_info build_id paper_name paper_size paper_sha paper_url
  build_info="$(resolve_build "$MC_VERSION")" || die "Could not resolve a Paper build for $MC_VERSION"
  IFS=$'\t' read -r build_id paper_name paper_size paper_sha paper_url <<< "$build_info"

  local paper_jar="$PAPER_CACHE_DIR/$MC_VERSION/$paper_name"
  if [[ -f "$paper_jar" ]]; then
    ok "Paper $MC_VERSION build $build_id already cached: $paper_name"
  else
    mkdir -p "$(dirname -- "$paper_jar")"
    info "Downloading Paper $MC_VERSION build $build_id ($paper_name)"
    fetch_to_file "$paper_url" "$paper_jar"
    if [[ -n "$paper_sha" ]]; then
      local actual
      actual="$(sha256_of "$paper_jar")"
      [[ "$actual" == "$paper_sha" ]] || die "Checksum mismatch for $paper_name (expected $paper_sha, got $actual)"
      ok "Checksum verified"
    fi
    # Keep only the build we are about to use for this Minecraft version.
    find "$(dirname -- "$paper_jar")" -maxdepth 1 -name 'paper-*.jar' ! -name "$paper_name" -delete
  fi

  local api_version api_artifact
  api_artifact="$(resolve_paper_api_version "$MC_VERSION" "$build_id")"
  [[ -n "$api_artifact" ]] || die "No paper-api artifact published for Minecraft $MC_VERSION"
  api_version="$(api_version_for "$MC_VERSION")"
  ok "Paper API $api_artifact (plugin api-version $api_version)"

  local plugin_jar=""
  plugin_jar="$(find_plugin_jar "$api_version" "$PLUGIN_VERSION" || true)"
  if (( ! DO_BUILD )); then
    [[ -n "$plugin_jar" ]] \
      || die "No plugin build for Minecraft $api_version in build/libs (run without --no-build first)"
    ok "Plugin JAR: $plugin_jar"
  elif (( FORCE_REBUILD )); then
    ok "Rebuilding the plugin for Minecraft $api_version (Paper API $api_artifact)"
    build_plugin "$gradle_bin" "$jdk" "$api_version" "$api_artifact" "$PLUGIN_VERSION"
    plugin_jar="$(find_plugin_jar "$api_version" "$PLUGIN_VERSION")" \
      || die "Gradle produced no MSS-*.jar in build/libs"
    ok "Plugin JAR: $plugin_jar"
  elif [[ -n "$plugin_jar" ]]; then
    ok "Plugin JAR: $plugin_jar"
    if confirm "Rebuild it for Minecraft $api_version (Paper API $api_artifact)?" n; then
      build_plugin "$gradle_bin" "$jdk" "$api_version" "$api_artifact" "$PLUGIN_VERSION"
      plugin_jar="$(find_plugin_jar "$api_version" "$PLUGIN_VERSION")" \
        || die "Gradle produced no MSS-*.jar in build/libs"
    else
      hint "keeping the existing build (--clean forces a full rebuild)"
    fi
  else
    warn "No plugin build for Minecraft $api_version yet"
    hint "minecraftVersion=$api_version  paperApiVersion=$api_artifact" \
         "${PLUGIN_VERSION:+,  pluginVersion=$PLUGIN_VERSION}"
    if ! confirm "Build the plugin now?" y; then
      die "The server needs the plugin JAR - run the script again and answer yes"
    fi
    build_plugin "$gradle_bin" "$jdk" "$api_version" "$api_artifact" "$PLUGIN_VERSION"
    plugin_jar="$(find_plugin_jar "$api_version" "$PLUGIN_VERSION")" \
      || die "Gradle produced no MSS-*.jar in build/libs"
    ok "Plugin JAR: $plugin_jar"
  fi

  # A world generated by another Minecraft version may not load any more.
  local marker="$SERVER_DIR/.paper-version" previous=""
  [[ -f "$marker" ]] && previous="$(cat "$marker")"
  if (( RESET_WORLD )); then
    if [[ -d "$SERVER_DIR/world" ]]; then
      rm -rf "$SERVER_DIR/world"
      ok "Existing world deleted (--reset-world)"
    fi
  elif [[ -n "$previous" && "$previous" != "$MC_VERSION" && -d "$SERVER_DIR/world" ]]; then
    warn "Paper version changed ($previous -> $MC_VERSION); the existing world was generated by $previous."
    hint "If the server refuses to start, reset it with: scripts/local-server.sh --version $MC_VERSION --reset-world"
  fi

  prepare_server_dir "$MC_VERSION" "$paper_jar" "$plugin_jar"

  printf '\n'
  info "Server directory: $SERVER_DIR"
  hint "world:  $SERVER_DIR/world"
  hint "logs:   $SERVER_DIR/logs"
  hint "connect to localhost:$SERVER_PORT (offline mode, any player name)"

  if (( ! DO_START )); then
    printf '\n'
    ok "Prepared. Start it with: scripts/local-server.sh --version $MC_VERSION"
    exit 0
  fi

  printf '\n'
  if (( DO_RESTART )); then
    stop_running_server || hint "no server started by this script was running"
    stop_screen_session || true
  fi
  if port_in_use "$SERVER_PORT"; then
    die "Port $SERVER_PORT is already in use - another server is running.
     Stop it (console: stop / Ctrl+C) or re-run with --restart."
  fi
  info "Starting Paper $MC_VERSION (first start generates the world, this takes a moment)"
  hint "in-game: /siege anchor, /siege setup, /siege start   -   console: stop"
  printf '\n'

  # Run the server inside a detached screen session so the console stays
  # reachable after this script exits.
  if command -v screen >/dev/null 2>&1; then
    local launcher="$SERVER_DIR/run-server.sh"
    cat > "$launcher" <<EOF
#!/usr/bin/env bash
# Written by scripts/local-server.sh - starts the local Paper server.
cd "\$(dirname "\$0")" || exit 1
printf '%s\n' "\$\$" > server.pid
exec "$jdk/bin/java" -Xms1G -Xmx2G -XX:+UseG1GC -jar paper.jar nogui
EOF
    chmod +x "$launcher"
    mkdir -p "$SERVER_DIR/logs"
    stop_screen_session || true
    screen -dmS "$SESSION_NAME" "$launcher" \
      || die "Could not start the screen session"
    ok "Console session '$SESSION_NAME' started"
    hint "attach:  screen -r $SESSION_NAME      (detach again with Ctrl+A D)"
    hint "log:     tail -f $SERVER_DIR/logs/latest.log"
    exit 0
  fi

  warn "screen is not installed - the console stays attached to this terminal"
  cd "$SERVER_DIR"
  printf '%s\n' "$$" > "$SERVER_DIR/server.pid"
  exec "$jdk/bin/java" -Xms1G -Xmx2G -XX:+UseG1GC -jar paper.jar nogui
}

main "$@"
