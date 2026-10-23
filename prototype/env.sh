FORGE_SRC="${FORGE_SRC:-$HOME/src/forge-v2}"
release="$HOME/apps/forge-2.0.15"
module_jar() {
  ls -t "$FORGE_SRC/forge-$1/target/forge-$1-"*.jar | grep -v -e sources -e javadoc -e with-dependencies | head -1
}
desktop="$(ls -t "$FORGE_SRC"/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar 2>/dev/null | head -1 || true)"
if [[ -n "$desktop" ]]; then
  FORGE_RES="$FORGE_SRC/forge-gui/res"
  app_tag="$(basename "$FORGE_SRC")"
else
  desktop="$release/forge-gui-desktop-2.0.15-jar-with-dependencies.jar"
  FORGE_RES="$release/res"
  app_tag="release-$(basename "$FORGE_SRC")"
fi
FORGE_CP="$HOME/.m2/repository/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar:$(module_jar core):$(module_jar game):$(module_jar ai):$desktop"
BUILD="$here/build/$app_tag"
