#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="$HOME/.local/share/smartreminder-cli"
BIN_DIR="$HOME/.local/bin"
CMD_NAME="smartreminder"

removed=0

if [ -f "$BIN_DIR/$CMD_NAME" ]; then
  rm "$BIN_DIR/$CMD_NAME"
  echo "Removed: $BIN_DIR/$CMD_NAME"
  removed=1
fi

if [ -d "$INSTALL_DIR" ]; then
  rm -rf "$INSTALL_DIR"
  echo "Removed: $INSTALL_DIR"
  removed=1
fi

if [ $removed -eq 0 ]; then
  echo "Nothing to uninstall."
fi
