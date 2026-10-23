#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
home="$1"; shift
jvm=()
while [[ $# -gt 0 && "$1" != "--" ]]; do jvm+=("$1"); shift; done
[[ "${1:-}" == "--" ]] && shift
# the fork's engine flags, none of them change a game (FORGE_FLAGS= to run without them)
standard_flags="-Dforge.ai.inlineEvaluation=true -Dforge.game.skipRedundantStateChecks=true -Dforge.game.cacheReplacementLists=true -Dforge.game.memoReplacementCandidates=true -Dforge.game.cacheStaticLists=true"
threads=1
for ((i = 1; i < $#; i++)); do
  [[ "${!i}" == "--threads" ]] && { j=$((i + 1)); threads="${!j}"; }
done
work="$(dirname "$here")/work/$app_tag-$home"
mkdir -p "$here/build"
(
  flock 9
  if [[ ! -f "$BUILD/.stamp" || -n "$(find "$here/src" -name '*.java' -newer "$BUILD/.stamp" | head -1)" ]]; then
    "$here/build.sh" >&2
  fi
) 9>"$BUILD.lock"
if [[ ! -d "$work" ]]; then
  mkdir -p "$work/user/preferences" "$work/cache"
  ln -s "$FORGE_RES" "$work/res"
  printf 'userDir=%s/user/\ncacheDir=%s/cache/\n' "$work" "$work" > "$work/forge.profile.properties"
  : > "$work/user/preferences/forge.preferences"
  [[ "$home" == *lazy* ]] && echo "LOAD_CARD_SCRIPTS_LAZILY=true" >> "$work/user/preferences/forge.preferences"
  [[ "$home" == *perf* ]] && echo "PERFORMANCE_MODE=true" >> "$work/user/preferences/forge.preferences"
fi
cd "$work"
# parallel gc: same games, about 7% less wall per game than g1 and 13% less process cpu (GC_FLAGS= for the jvm default)
exec java -Xmx$(( threads > 1 ? threads + 4 : 3 ))g -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -XX:-DontCompileHugeMethods ${GC_FLAGS--XX:+UseParallelGC} ${FORGE_FLAGS-$standard_flags} "${jvm[@]}" -cp "$BUILD:$FORGE_CP" forge.goldfish.Runner "$@"
