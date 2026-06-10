#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

INSTALL_DIR="$HOME/.local/share/smartagent"
BIN_DIR="$HOME/.local/bin"
JAR_NAME="smartagent.jar"
CMD_NAME="smartreminder"

echo "Building fat JAR..."
cd "$REPO_ROOT"
./gradlew :smartagent:shadowJar --console=plain -q

SRC_JAR="$REPO_ROOT/smartagent/build/libs/$JAR_NAME"
if [ ! -f "$SRC_JAR" ]; then
  echo "Error: JAR not found at $SRC_JAR" >&2
  exit 1
fi

echo "Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "$SRC_JAR" "$INSTALL_DIR/$JAR_NAME"

CONFIG_DIR="$HOME/.config/smartagent"
CONFIG_FILE="$CONFIG_DIR/local.properties"
mkdir -p "$CONFIG_DIR"

if [ ! -f "$CONFIG_FILE" ]; then
  echo ""
  echo "API keys not found. Enter to skip (set later in $CONFIG_FILE)."
  read -r -p "DEEPSEEK_STUDY_API_KEY: " deepseek_key
  read -r -p "OPENROUTER_STUDY_API_KEY: " openrouter_key
  {
    [ -n "$deepseek_key" ]    && echo "DEEPSEEK_STUDY_API_KEY=$deepseek_key"
    [ -n "$openrouter_key" ]  && echo "OPENROUTER_STUDY_API_KEY=$openrouter_key"
  } > "$CONFIG_FILE"
  echo "Saved to $CONFIG_FILE"
fi

mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/$CMD_NAME" << EOF
#!/usr/bin/env bash
PROPS="$INSTALL_DIR/local.properties"
if [ -f "\$PROPS" ]; then
  while IFS= read -r line; do
    [[ "\$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "\${line//[[:space:]]/}" ]] && continue
    export "\${line%%=*}=\${line#*=}"
  done < "\$PROPS"
fi
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
