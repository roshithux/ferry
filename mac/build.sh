#!/bin/zsh
# Builds Ferry.app into mac/build/ (no Xcode needed: SwiftPM + swiftc + an ad-hoc signature).
#   ARCHS="arm64 x86_64" ./build.sh   universal build, for other Macs (default: this Mac's arch)
#   INSTALL=0 ./build.sh               don't touch /Applications or register with macOS
set -euo pipefail
cd "$(dirname "$0")"

archs=(${=ARCHS:-$(uname -m)})
app_bins=()
share_bins=()
for arch in $archs; do
    swift build -c release --triple "$arch-apple-macosx14.0" --scratch-path ".build/$arch"
    app_bins+=(".build/$arch/release/Ferry")

    # The Share extension ("Share → Ferry"); it reuses the app's wire format.
    mkdir -p ".build/share/$arch"
    swiftc -O -module-name FerryShare -parse-as-library \
        -target "$arch-apple-macos14.0" \
        -Xlinker -e -Xlinker _NSExtensionMain \
        ShareExtension/ShareViewController.swift Sources/Ferry/Wire.swift \
        -o ".build/share/$arch/FerryShare"
    share_bins+=(".build/share/$arch/FerryShare")
done

app=build/Ferry.app
appex=$app/Contents/PlugIns/FerryShare.appex
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$appex/Contents/MacOS"
lipo -create $app_bins -output "$app/Contents/MacOS/Ferry"
cp Info.plist "$app/Contents/Info.plist"
cp Resources/AppIcon.icns "$app/Contents/Resources/AppIcon.icns"
lipo -create $share_bins -output "$appex/Contents/MacOS/FerryShare"
cp ShareExtension/Info.plist "$appex/Contents/Info.plist"

# Sign the extension (sandboxed) first, then the app around it.
codesign --force --sign - --entitlements ShareExtension/FerryShare.entitlements "$appex"
codesign --force --sign - "$app"
echo "Built $PWD/$app ($archs)"

[[ ${INSTALL:-1} == 1 ]] || exit 0

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
