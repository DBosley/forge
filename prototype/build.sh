#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
rm -rf "$BUILD"
mkdir -p "$BUILD"
javac --release 17 -nowarn -cp "$FORGE_CP" -d "$BUILD" $(find "$here/src" -name '*.java')
touch "$BUILD/.stamp"
