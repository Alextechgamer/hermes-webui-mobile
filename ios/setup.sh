#!/usr/bin/env bash
# Generate HermesWebUI.xcodeproj. Run on a Mac.
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v xcodegen >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    brew install xcodegen
  else
    echo "Install XcodeGen: https://github.com/yonaskolb/XcodeGen" >&2
    exit 1
  fi
fi
xcodegen generate
echo "Open with:  open HermesWebUI.xcodeproj"
