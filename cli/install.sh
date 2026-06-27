#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

INSTALL_DIR="$HOME/.local/share/smartreminder-cli"
BIN_DIR="$HOME/.local/bin"
JAR_NAME="smartreminder-cli.jar"
CMD_NAME="smartreminder"

echo "Building fat JAR..."
cd "$REPO_ROOT"
./gradlew :cli:shadowJar --console=plain -q

SRC_JAR="$REPO_ROOT/cli/build/libs/$JAR_NAME"
if [ ! -f "$SRC_JAR" ]; then
  echo "Error: JAR not found at $SRC_JAR" >&2
  exit 1
fi

echo "Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "$SRC_JAR" "$INSTALL_DIR/$JAR_NAME"

mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/$CMD_NAME" << EOF
#!/usr/bin/env bash
# API keys can be passed via environment or local.properties next to this script
exec java -jar "$INSTALL_DIR/$JAR_NAME" "\$@"
EOF
chmod +x "$BIN_DIR/$CMD_NAME"

echo ""
echo "Installed: $BIN_DIR/$CMD_NAME"
echo ""

if ! echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
  echo "Add to your shell profile (~/.zshrc or ~/.bashrc):"
  echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
  echo ""
fi

echo "Usage:"
echo "  $CMD_NAME                        # start chat (deepseek)"
echo "  $CMD_NAME --model qwen           # start with qwen"
echo "  $CMD_NAME --repo /path/to/repo   # start with repo context"
echo ""
echo "API keys: set DEEPSEEK_STUDY_API_KEY / OPENROUTER_STUDY_API_KEY in environment"
echo "or place local.properties in the working directory."
