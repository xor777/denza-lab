#!/bin/bash
# Publish the download page to GitHub Pages (CAG-015): www/ and the Luminofor
# fonts become one orphan commit, force-pushed to the gh-pages branch, which
# Pages serves at https://xor777.github.io/denza-lab/. main is not pushed.
# The page reads the releases itself, so a new APK needs only a GitHub release;
# run this script only when the page changes. A custom domain is a CNAME file
# in www/.
set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
site=$(mktemp -d)
trap 'rm -rf -- "${site:?}"' EXIT

cp -R "$root/platform/downloads/www/." "$site/"
mkdir "$site/fonts"
for font in Jura-Medium.ttf Jura-OFL.txt Roboto-400.ttf Roboto-500.ttf Roboto-LICENSE.txt; do
  cp "$root/tools/design-canvas/luminofor/fonts/$font" "$site/fonts/"
done
touch "$site/.nojekyll"

source=$(git -C "$root" rev-parse --short HEAD)
git -C "$site" init -q -b gh-pages
git -C "$site" add -A
git -C "$site" -c user.name="$(git -C "$root" config user.name)" -c user.email="$(git -C "$root" config user.email)" \
  commit -q -m "Download page from $source"
git -C "$site" push -q --force "$(git -C "$root" remote get-url origin)" gh-pages:gh-pages
echo "gh-pages <- platform/downloads/www at $source"
