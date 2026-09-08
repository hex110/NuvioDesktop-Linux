#!/usr/bin/env bash
# Compile the Linux native player bridge (libplayer_bridge.so).
#
# Invoked by the buildLinuxPlayerBridge Gradle task, and runnable by hand for a
# fast syntax check without going through Gradle:
#
#   scripts/linux/build-player-bridge.sh <source.cpp> <output.so> [java-home]
#
# Build deps (Arch): jdk17-openjdk gtk3 webkit2gtk-4.1 mpv libx11 libxcomposite cairo
set -euo pipefail

src="${1:?usage: build-player-bridge.sh <source.cpp> <output.so> [java-home]}"
out="${2:?usage: build-player-bridge.sh <source.cpp> <output.so> [java-home]}"
java_home="${3:-${JAVA_HOME:-}}"

if [ -z "$java_home" ] || [ ! -f "$java_home/include/jni.h" ]; then
    for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/default /usr/lib/jvm/java-21-openjdk; do
        if [ -f "$candidate/include/jni.h" ]; then java_home="$candidate"; break; fi
    done
fi
[ -f "$java_home/include/jni.h" ] || { echo "build-player-bridge: no jni.h; set JAVA_HOME" >&2; exit 1; }

pkgs=(gtk+-3.0 webkit2gtk-4.1 mpv x11 xcomposite cairo gdk-x11-3.0)
missing=()
for p in "${pkgs[@]}"; do pkg-config --exists "$p" || missing+=("$p"); done
if [ ${#missing[@]} -gt 0 ]; then
    echo "build-player-bridge: missing pkg-config packages: ${missing[*]}" >&2
    exit 1
fi

mkdir -p "$(dirname "$out")"
exec g++ -std=c++17 -fPIC -shared -O2 \
    -I"$java_home/include" -I"$java_home/include/linux" \
    $(pkg-config --cflags "${pkgs[@]}") \
    "$src" -o "$out" \
    $(pkg-config --libs "${pkgs[@]}")
