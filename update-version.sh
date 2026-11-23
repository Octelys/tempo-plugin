#!/bin/bash
# Stamps the plugin version into gradle.properties and plugin.xml. Used by CI only:
# both files keep `_PLACEHOLDER_` in source control.

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

newVersion=$1
root="$(cd "$(dirname "$0")" && pwd)"

echo "Updating the version to $newVersion for files in $root"

for file in $(find "$root/src" -name "plugin.xml"); do
  echo "Editing file: $file"
  ed -s "$file" <<< $'H\n,s/<version>_PLACEHOLDER_<\\/version>/<version>'"$newVersion"$'<\\/version>/g\nw'
done

echo "Editing file: $root/gradle.properties"
ed -s "$root/gradle.properties" <<< $'H\n,s/PluginVersion=_PLACEHOLDER_/PluginVersion='"$newVersion"$'/g\nw'
