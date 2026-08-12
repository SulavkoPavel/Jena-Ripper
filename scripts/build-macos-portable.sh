#!/bin/bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script must run on macOS." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$PROJECT_ROOT/jena-ripper-backend/pom.xml" ]]; then
  BACKEND_DIR="$PROJECT_ROOT/jena-ripper-backend"
  FRONTEND_DIR="$PROJECT_ROOT/jena-ripper-frontend"
else
  BACKEND_DIR="$PROJECT_ROOT/backend"
  FRONTEND_DIR="$PROJECT_ROOT/frontend"
fi

if [[ ! -f "$BACKEND_DIR/pom.xml" || ! -f "$FRONTEND_DIR/package.json" ]]; then
  echo "Backend or frontend project directory was not found." >&2
  exit 1
fi

if [[ "$(uname -m)" == "arm64" ]]; then
  ARCH="arm64"
elif [[ "$(uname -m)" == "x86_64" ]]; then
  ARCH="x64"
else
  echo "Unsupported macOS architecture: $(uname -m)" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
JLINK="$JAVA_HOME/bin/jlink"
if [[ ! -x "$JLINK" ]]; then
  echo "jlink was not found in JAVA_HOME=$JAVA_HOME" >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven was not found." >&2
  exit 1
fi
RELEASE_VERSION="$(cd "$BACKEND_DIR" && mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
if [[ -z "$RELEASE_VERSION" ]]; then
  echo "Project version was not found in backend pom.xml." >&2
  exit 1
fi

RELEASE_DIR="$PROJECT_ROOT/release"
BUILD_ROOT="$RELEASE_DIR/.portable-build/macos-$ARCH"
PACKAGE_ROOT="$BUILD_ROOT/Jena-Ripper"
RUNTIME_DIR="$PACKAGE_ROOT/runtime"
ZIP_PATH="$RELEASE_DIR/Jena-Ripper-$RELEASE_VERSION-macOS-$ARCH.zip"
TEMPLATE_DIR="$PROJECT_ROOT/packaging/portable/macos"

case "$BUILD_ROOT" in
  "$RELEASE_DIR"/.portable-build/*) rm -rf -- "$BUILD_ROOT" ;;
  *) echo "Refusing to remove a directory outside release: $BUILD_ROOT" >&2; exit 1 ;;
esac
mkdir -p "$PACKAGE_ROOT"

(
  cd "$BACKEND_DIR"
  mvn clean package -Prelease "-Dfrontend.directory=$FRONTEND_DIR"
)

MODULES="java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.unsupported,jdk.zipfs"
"$JLINK" --add-modules "$MODULES" --output "$RUNTIME_DIR" --strip-debug --no-header-files --no-man-pages --compress=2

cp "$BACKEND_DIR/target/jena-ripper.jar" "$PACKAGE_ROOT/jena-ripper.jar"
cp "$TEMPLATE_DIR/Jena Ripper.command" "$PACKAGE_ROOT/Jena Ripper.command"
cp "$TEMPLATE_DIR/README.txt" "$PACKAGE_ROOT/README.txt"
chmod +x "$PACKAGE_ROOT/Jena Ripper.command"

if [[ ! -x "$RUNTIME_DIR/bin/java" ]]; then
  echo "Portable runtime does not contain runtime/bin/java." >&2
  exit 1
fi

rm -f -- "$ZIP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$PACKAGE_ROOT" "$ZIP_PATH"
echo "Portable ZIP: $ZIP_PATH"
