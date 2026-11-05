#!/usr/bin/env bash
# Holt das offizielle Nothing Glyph Developer Kit nach app/libs.
#
# Warum nicht committet: die AAR ist Nothing-eigenes Material (GitHub
# Nothing-Developer-Programme/Glyph-Developer-Kit) und steht unter der
# Glyph-SDK-EULA, nicht unter unserer Lizenz. Sie wird bei Bedarf geladen,
# statt weiterverteilt zu werden.
#
# Warum mit Hash: der Dateiname ist KEINE Version. Unter dem Namen
# `glyph-matrix-sdk-2.0.aar` liefen bereits zwei verschiedene Binaries:
#
#   2026-03  58.757 B  ohne Code_25131 / is25131 (Phone (4b) fehlt)
#   2026-07 114.689 B  vollstaendig, alle neun Geraete-Keys
#
# Ohne Pruefsumme baut ein frischer Klon je nach Upstream-Stand gegen eine
# andere API — und der Fehler faellt erst beim 4b-Tabellenaufbau auf.
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p app/libs

URL="https://raw.githubusercontent.com/Nothing-Developer-Programme/Glyph-Developer-Kit/main/sdk/glyph-matrix-sdk-2.0.aar"
TARGET="app/libs/glyph-matrix-sdk-2.0.aar"
# Gepinnt auf den Stand 2026-07-16 (vollstaendiger Satz, alle neun DEVICE_*-Keys).
EXPECTED_SHA256="329393019db5f0f987c6245855d13fa273d06756c68829ca0f6ae686ba336da1"
EXPECTED_SIZE=114689

# Ergebnis auf stderr, damit $(...) nur den Pfad liefert.
log() { echo "$@" >&2; }

verify() {
  local file="$1" size sha
  size=$(stat -c %s "$file")
  if [ "$size" != "$EXPECTED_SIZE" ]; then
    log "  Groesse $size, erwartet $EXPECTED_SIZE"
    return 1
  fi
  sha=$(sha256sum "$file" | cut -d' ' -f1)
  if [ "$sha" != "$EXPECTED_SHA256" ]; then
    log "  SHA-256 $sha"
    log "  erwartet $EXPECTED_SHA256"
    return 1
  fi
  return 0
}

if [ -f "$TARGET" ] && verify "$TARGET"; then
  log "vorhanden und geprueft: $TARGET ($EXPECTED_SIZE Bytes)"
  echo "$TARGET"
  exit 0
fi

if [ -f "$TARGET" ]; then
  log "vorhandene Datei weicht von der gepinnten Version ab - lade neu: $TARGET"
else
  log "lade $URL"
fi

curl -fsSL -o "$TARGET.part" "$URL"
if ! verify "$TARGET.part"; then
  rm -f "$TARGET.part"
  log "FEHLER: Upstream liefert eine andere Datei als gepinnt."
  log "Pruefe upstream auf einen neuen Stand und aktualisiere EXPECTED_SHA256"
  log "bewusst (inkl. Testlauf), statt die Pruefung zu entfernen."
  exit 1
fi
mv "$TARGET.part" "$TARGET"
log "ok: $TARGET ($EXPECTED_SIZE Bytes, sha256 geprueft)"
echo "$TARGET"
