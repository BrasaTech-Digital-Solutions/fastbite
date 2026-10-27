#!/usr/bin/env bash
# Signs FastBite's declaración responsable for release x.y.z (Orden HAC/1177/2024, art. 15; one signed declaration per
# version, see docs/legal/README.md, answer D3). Run it on Ricardo's machine during "release x.y.z", after the version
# is set and before the image is built: the signed PDF is built into the image, and the app shows it.
#
#   scripts/sign-declaration.sh 1.2.0 [--yes]
#
# Needs BRASATECH_CERTIFICATE_PATH (the .pfx/.p12) and BRASATECH_CERTIFICATE_PASSWORD; the password is read by the
# signer from the environment and never printed. Writes, next to the app's resources:
#   fastbite/adapter-in/web/src/main/resources/declarations/FastBite-x.y.z.pdf         the signed declaration (PAdES)
#   fastbite/adapter-in/web/src/main/resources/declarations/FastBite-x.y.z.properties  date, place, signatory, signer
#   fastbite/adapter-in/web/src/main/resources/declarations/declarations.list          every signed version
set -euo pipefail

VERSION="${1:-}"
CONFIRMED="${2:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: scripts/sign-declaration.sh x.y.z [--yes]" >&2
  exit 2
fi

REPO=$(cd "$(dirname "$0")/.." && pwd)
DECLARATIONS="$REPO/fastbite/adapter-in/web/src/main/resources/declarations"
PDF="$DECLARATIONS/FastBite-$VERSION.pdf"
if [ -e "$PDF" ]; then
  echo "FastBite $VERSION already has a signed declaration: $PDF" >&2
  exit 1
fi
for variable in BRASATECH_CERTIFICATE_PATH BRASATECH_CERTIFICATE_PASSWORD; do
  if [ -z "${!variable:-}" ]; then
    echo "Set $variable first." >&2
    exit 1
  fi
done

# Paths for Windows programs (Java, Chrome) when running in Git Bash
win() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

CHROME=""
for candidate in "/c/Program Files/Google/Chrome/Application/chrome.exe" \
                 "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
                 "$(command -v google-chrome 2>/dev/null || true)" "$(command -v chromium 2>/dev/null || true)"; do
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then CHROME="$candidate"; break; fi
done
if [ -z "$CHROME" ]; then
  echo "Chrome or Edge is needed to print the declaration." >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== Building the signer (tools/declaration-signer)"
mvn -o -q -f "$(win "$REPO/tools/declaration-signer/pom.xml")" package 2>/dev/null \
  || mvn -q -f "$(win "$REPO/tools/declaration-signer/pom.xml")" package
JAR="$(win "$REPO/tools/declaration-signer/target/declaration-signer.jar")"

echo "== Rendering the declaration for FastBite $VERSION"
java -jar "$JAR" render --repo "$(win "$REPO")" --version "$VERSION" --out "$(win "$WORK")" 2> >(grep -v "^SLF4J" >&2)

echo "== Printing it to PDF"
"$CHROME" --headless=new --disable-gpu --no-pdf-header-footer --user-data-dir="$(win "$WORK/chrome")" \
  --print-to-pdf="$(win "$WORK/declaration.pdf")" "file:///$(win "$WORK/declaration.html")" >/dev/null 2>&1 || true
if [ ! -s "$WORK/declaration.pdf" ]; then
  echo "Chrome did not print the declaration." >&2
  exit 1
fi

if [ "$CONFIRMED" != "--yes" ]; then
  read -r -p "Sign the declaración responsable for FastBite $VERSION with your certificate? [y/N] " answer
  [[ "$answer" =~ ^[yYsS]$ ]] || { echo "Not signed."; exit 1; }
fi

echo "== Signing"
java -jar "$JAR" sign --details "$(win "$WORK/details.properties")" --in "$(win "$WORK/declaration.pdf")" \
  --out "$(win "$PDF")" 2> >(grep -v "^SLF4J" >&2)

echo "== Verifying"
java -jar "$JAR" verify --in "$(win "$PDF")" 2> >(grep -v "^SLF4J" >&2)

# The app reads this list: a native image can't search its resources
LIST="$DECLARATIONS/declarations.list"
grep -qx "$VERSION" "$LIST" 2>/dev/null || echo "$VERSION" >> "$LIST"
echo "Done. Commit declarations/FastBite-$VERSION.pdf, its .properties and declarations.list with the release."
