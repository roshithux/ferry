#!/bin/zsh
# Builds Ferry.app into mac/build/ (no Xcode needed: SwiftPM + swiftc + an ad-hoc signature).
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release

# The Share extension ("Share → Ferry"); it reuses the app's wire format.
mkdir -p .build/share
swiftc -O -module-name FerryShare -parse-as-library \
    -target "$(uname -m)-apple-macos14.0" \
    -Xlinker -e -Xlinker _NSExtensionMain \
    ShareExtension/ShareViewController.swift Sources/Ferry/Wire.swift \
    -o .build/share/FerryShare

app=build/Ferry.app
appex=$app/Contents/PlugIns/FerryShare.appex
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$appex/Contents/MacOS"
cp .build/release/Ferry "$app/Contents/MacOS/Ferry"
cp Info.plist "$app/Contents/Info.plist"
cp Resources/AppIcon.icns "$app/Contents/Resources/AppIcon.icns"
cp .build/share/FerryShare "$appex/Contents/MacOS/FerryShare"
cp ShareExtension/Info.plist "$appex/Contents/Info.plist"

# Sign the extension (sandboxed) first, then the app around it.
codesign --force --sign - --entitlements ShareExtension/FerryShare.entitlements "$appex"
codesign --force --sign - "$app"
echo "Built $PWD/$app"

# Keep an installed copy current, so the old version isn't the one that opens.
if [[ -d /Applications/Ferry.app ]]; then
    pkill -x Ferry || true
    rm -rf /Applications/Ferry.app
    cp -R "$app" /Applications/Ferry.app
    installed=/Applications/Ferry.app
    echo "Updated /Applications/Ferry.app"
else
    installed=$PWD/$app
fi

# Register the app (icon) and its Share extension with macOS.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$installed"
pluginkit -a "$installed/Contents/PlugIns/FerryShare.appex"
