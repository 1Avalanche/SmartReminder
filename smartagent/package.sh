#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$REPO_ROOT/smartagent/build"
DIST_DIR="$BUILD_DIR/dist"
APP_NAME="SmartAgent"
JAR_NAME="smartagent.jar"
JAR_PATH="$BUILD_DIR/libs/$JAR_NAME"

# Resolve real JDK home — handles sdkman/homebrew/any symlink chain
JAVA_HOME_RAW=$(java -XshowSettings:property -version 2>&1 | grep 'java.home' | sed 's/.*= //')
JAVA_HOME_REAL=$(cd "$JAVA_HOME_RAW" && pwd -P)
echo "==> JDK: $JAVA_HOME_REAL"

echo "==> Building JAR..."
cd "$REPO_ROOT"
./gradlew :smartagent:shadowJar --console=plain -q

echo "==> Detecting required Java modules..."
MODULES=$("$JAVA_HOME_REAL/bin/jdeps" \
  --multi-release 17 \
  --ignore-missing-deps \
  --print-module-deps \
  "$JAR_PATH" 2>/dev/null || echo "java.base")
# HTTPS needs crypto modules; Okio/OkHttp may need jdk.unsupported for Unsafe
MODULES="${MODULES},jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported"
echo "    modules: $MODULES"

echo "==> Building minimal JRE with jlink..."
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
"$JAVA_HOME_REAL/bin/jlink" \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --strip-debug \
  --add-modules "$MODULES" \
  --output "$DIST_DIR/runtime"

echo "==> Assembling .app bundle..."
APP="$DIST_DIR/$APP_NAME.app"
mkdir -p "$APP/Contents/MacOS"
mkdir -p "$APP/Contents/Resources"

cp "$JAR_PATH" "$APP/Contents/Resources/$JAR_NAME"
cp -r "$DIST_DIR/runtime" "$APP/Contents/runtime"

if [ -f "$REPO_ROOT/local.properties" ]; then
  cp "$REPO_ROOT/local.properties" "$APP/Contents/Resources/local.properties"
  echo "    bundled local.properties (API keys)"
else
  echo "Warning: local.properties not found — API keys not bundled. Set env vars manually."
fi

cat > "$APP/Contents/Info.plist" << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>SmartAgent</string>
    <key>CFBundleDisplayName</key><string>SmartAgent</string>
    <key>CFBundleExecutable</key><string>SmartAgent</string>
    <key>CFBundleIdentifier</key><string>com.smartreminder.smartagent</string>
    <key>CFBundleVersion</key><string>1.0</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>LSMinimumSystemVersion</key><string>12.0</string>
</dict>
</plist>
PLIST

# Launcher: uses only the bundled JRE — no JAVA_HOME, no sdkman, no system java
cat > "$APP/Contents/MacOS/SmartAgent" << 'LAUNCHER'
#!/bin/bash
BUNDLE="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="$BUNDLE/runtime/bin/java"
JAR="$BUNDLE/Resources/smartagent.jar"

load_props() {
  local f="$1"
  [ -f "$f" ] || return
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line//[[:space:]]/}" ]] && continue
    printf 'export %s="%s"\n' "${line%%=*}" "${line#*=}"
  done < "$f"
}

TMPSCRIPT=$(mktemp -t smartagent)
{
  printf '#!/bin/bash\n'
  load_props "$BUNDLE/Resources/local.properties"
  load_props "$HOME/.config/smartagent/local.properties"
  printf '"%s" -jar "%s"\n' "$JAVA" "$JAR"
} > "$TMPSCRIPT"
chmod +x "$TMPSCRIPT"

osascript \
  -e "tell application \"Terminal\" to activate" \
  -e "tell application \"Terminal\" to do script \"$TMPSCRIPT\""
LAUNCHER
chmod +x "$APP/Contents/MacOS/SmartAgent"

echo "==> Creating DMG..."
DMG="$DIST_DIR/$APP_NAME.dmg"
hdiutil detach "/Volumes/$APP_NAME" 2>/dev/null || true
hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "$APP" \
  -ov -format UDZO \
  "$DMG" > /dev/null

echo ""
echo "Built: $DMG"
echo "Size:  $(du -sh "$DMG" | cut -f1)"
echo ""
echo "Install: open DMG → drag SmartAgent to Applications"
echo "Run:     double-click SmartAgent"
echo "Note:    first launch → right-click → Open  (app is unsigned)"
