#!/usr/bin/env bash
# Puts the newest signed uBlock Origin for Firefox into app assets, unpacked,
# so Umbra can load it as a built-in extension.
set -euo pipefail
dest="app/src/main/assets/extensions/ublock"
tmp="$(mktemp -d)"
gh release download --repo gorhill/uBlock --pattern '*.firefox.signed.xpi' --dir "$tmp"
rm -rf "$dest"; mkdir -p "$dest"
unzip -q "$tmp"/*.xpi -d "$dest"
grep -q '"uBlock0@raymondhill.net"' "$dest/manifest.json"
echo "uBlock Origin $(ls "$tmp")"
