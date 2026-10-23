#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
home="$1"; shift
jvm=()
while [[ $# -gt 0 && "$1" != "--" ]]; do jvm+=("$1"); shift; done
[[ "${1:-}" == "--" ]] && shift
work="$(dirname "$here")/work/home-$home"
if [[ ! -d "$work" ]]; then
  mkdir -p "$work/user/preferences" "$work/cache"
  ln -s "$FORGE_APP/res" "$work/res"
  printf 'userDir=%s/user/\ncacheDir=%s/cache/\n' "$work" "$work" > "$work/forge.profile.properties"
  : > "$work/user/preferences/forge.preferences"
  [[ "$home" == *lazy* ]] && echo "LOAD_CARD_SCRIPTS_LAZILY=true" >> "$work/user/preferences/forge.preferences"
  [[ "$home" == *perf* ]] && echo "PERFORMANCE_MODE=true" >> "$work/user/preferences/forge.preferences"
fi
cd "$work"
exec java -Xmx3g -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 "${jvm[@]}" -cp "$here/build:$FORGE_CP" forge.goldfish.Runner "$@"
