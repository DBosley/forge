#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
rm -rf "$here/build"
mkdir -p "$here/build"
javac --release 17 -nowarn -cp "$FORGE_CP" -d "$here/build" $(find "$here/src" -name '*.java')
