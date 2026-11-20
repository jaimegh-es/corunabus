#!/bin/bash
# Abre el MIDlet en MicroEmulator para probarlo sin tocar el Nokia.
#
#   legacy/j2me/emulator.sh           # usa el JAR ya compilado en dist/
#   legacy/j2me/emulator.sh --build   # recompila antes de abrir
#   legacy/j2me/emulator.sh --phone   # con la skin de teléfono (ventana pequeña)
#
# Por defecto abre una ventana grande y redimensionable, sin skin.
#
# Necesita java (JRE 8 o superior) y curl la primera vez. En Wayland/X11
# usa el DISPLAY actual; si no hay, se aborta con un aviso.

set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DIST="$HERE/dist"
ME_DIR="$HERE/.emulator"
ME_JAR="$ME_DIR/microemulator-2.0.4.jar"
ME_URL="https://repo1.maven.org/maven2/org/microemu/microemulator/2.0.4/microemulator-2.0.4.jar"

if [ "$1" = "--build" ] || [ ! -f "$DIST/corunabus.jar" ]; then
  VERSION="$(node -p "require('$ROOT/package.json').version" 2>/dev/null || echo 0.0.0)"
  "$HERE/build.sh" "$VERSION"
fi

if [ ! -f "$ME_JAR" ]; then
  echo "Descargando MicroEmulator..."
  mkdir -p "$ME_DIR"
  curl -fsSL -o "$ME_JAR" "$ME_URL"
fi

if [ -z "$DISPLAY" ]; then
  echo "No hay DISPLAY. Lanza esto desde tu sesión gráfica." >&2
  exit 1
fi

# MicroEmulator no sabe abrir rutas con caracteres no ASCII ("coruñabus"),
# así que se trabaja sobre una copia en un directorio temporal simple.
WORK="$(mktemp -d /tmp/corunabus-emu.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
cp "$DIST/corunabus.jar" "$WORK/app.jar"

# Por defecto, ventana grande redimensionable; --phone usa la skin.
EXTRA=(--device org/microemu/device/j2se/device.j2se --resizableDevice 360 640)
for arg in "$@"; do
  [ "$arg" = "--phone" ] && EXTRA=()
done

exec java -jar "$ME_JAR" "file://$WORK/app.jar" --rms memory "${EXTRA[@]}"
