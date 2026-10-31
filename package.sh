#!/bin/zsh
# Packages Ferry for another Mac + Android phone: dist/Ferry-<version>.zip holds
# Ferry.app (Intel + Apple Silicon), Ferry.apk and the install guide.
set -euo pipefail
cd "$(dirname "$0")"

version=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" mac/Info.plist)
name=Ferry-$version
out=dist/$name

ARCHS="arm64 x86_64" INSTALL=0 mac/build.sh
(cd android && ./gradlew -q assembleDebug)

rm -rf "$out" "dist/$name.zip"
mkdir -p "$out"
ditto mac/build/Ferry.app "$out/Ferry.app"
cp android/app/build/outputs/apk/debug/app-debug.apk "$out/Ferry.apk"
cp INSTALL.md "$out/How to install.md"

# ditto keeps the app's signature and permissions intact inside the zip.
ditto -c -k --keepParent "$out" "dist/$name.zip"
echo "Packaged $PWD/dist/$name.zip"
