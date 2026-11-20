#!/usr/bin/env bash
# Construye corunabus.jar + corunabus.jad para el Nokia E71.
#
# - javac compila contra el API de MIDP 2.0 (microemu-midp) y unos stubs de
#   JSR-179 que NO se empaquetan: el E71 trae la implementación real.
# - ProGuard convierte las clases a bytecode CLDC 1.1 (major 48) y las
#   preverifica (-microedition), que es lo que espera el S60.
# - jar/jad se generan con el tamaño exacto del jar.
#
# Uso: legacy/j2me/build.sh [version]
set -euo pipefail

cd "$(dirname "$0")"

VERSION="${1:-1.0.0}"
TOOLS=".tools"
BUILD="build"
DIST="dist"

MIDP_JAR="$TOOLS/microemu-midp-2.0.4.jar"
PROGUARD_JARS=(
  "$TOOLS/proguard-base-7.6.1.jar"
  "$TOOLS/proguard-core-9.1.7.jar"
  "$TOOLS/gson-2.11.0.jar"
  "$TOOLS/log4j-api-2.24.2.jar"
  "$TOOLS/log4j-core-2.24.2.jar"
)
MAVEN="https://repo1.maven.org/maven2"

download() {
  local url="$1" dest="$2"
  if [ -s "$dest" ]; then return; fi
  echo "descargando $(basename "$dest")"
  curl -fsSL "$url" -o "$dest"
}

mkdir -p "$TOOLS" "$DIST"
download "$MAVEN/org/microemu/microemu-midp/2.0.4/microemu-midp-2.0.4.jar" "$MIDP_JAR"
download "$MAVEN/com/guardsquare/proguard-base/7.6.1/proguard-base-7.6.1.jar" "${PROGUARD_JARS[0]}"
download "$MAVEN/com/guardsquare/proguard-core/9.1.7/proguard-core-9.1.7.jar" "${PROGUARD_JARS[1]}"
download "$MAVEN/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" "${PROGUARD_JARS[2]}"
download "$MAVEN/org/apache/logging/log4j/log4j-api/2.24.2/log4j-api-2.24.2.jar" "${PROGUARD_JARS[3]}"
download "$MAVEN/org/apache/logging/log4j/log4j-core/2.24.2/log4j-core-2.24.2.jar" "${PROGUARD_JARS[4]}"

# SDK de Java: en este contenedor JAVA_HOME puede estar vacío.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JH="$JAVA_HOME"
else
  JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
echo "Java: $JH"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/stubs"

# Stubs de JSR-179: solo para compilar.
find stubs -name '*.java' > "$BUILD/stubs.txt"
javac --release 8 -nowarn -d "$BUILD/stubs" @"$BUILD/stubs.txt"

# Código de la app.
find src -name '*.java' > "$BUILD/src.txt"
javac --release 8 -nowarn \
  -cp "$MIDP_JAR:$BUILD/stubs" \
  -d "$BUILD/classes" @"$BUILD/src.txt"

# ProGuard resuelve las rutas relativas al fichero de configuración: absolutas.
ROOT="$(pwd)"
cat > "$BUILD/proguard.conf" <<EOF
-injars $ROOT/$BUILD/classes
-outjars $ROOT/$BUILD/obf
-libraryjars $ROOT/$MIDP_JAR
-libraryjars $ROOT/$BUILD/stubs
-libraryjars $JH/jmods/java.base.jmod(!**.jar;!module-info.class)
-dontshrink
-dontoptimize
-dontobfuscate
-dontwarn
-microedition
-target 1.4
-keep public class corunabus.BusMIDlet { public *; }
-keep class corunabus.** { *; }
EOF

java -cp "$(IFS=:; echo "${PROGUARD_JARS[*]}")" proguard.ProGuard @"$BUILD/proguard.conf"

# Comprobación de que el bytecode es CLDC (major 48) y no falta el MIDlet.
if ! javap -verbose -cp "$BUILD/obf" corunabus.BusMIDlet | grep -q 'major version: 48'; then
  echo "ERROR: las clases no son CLDC 1.1 (major 48)" >&2
  exit 1
fi

cp res/icon.png "$BUILD/obf/icon.png"

cat > "$BUILD/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
MIDlet-1: Coruna Bus, /icon.png, corunabus.BusMIDlet
MIDlet-Name: Coruna Bus
MIDlet-Vendor: Coruna Bus
MIDlet-Version: $VERSION
MIDlet-Permissions: javax.microedition.io.Connector.http
MIDlet-Permissions-Opt: javax.microedition.io.Connector.https,javax.microedition.location.Location
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
EOF

rm -f "$DIST/corunabus.jar" "$DIST/corunabus.jad"
(cd "$BUILD/obf" && jar cfm "../../$DIST/corunabus.jar" ../MANIFEST.MF .)
JAR_SIZE=$(wc -c < "$DIST/corunabus.jar" | tr -d ' ')

cat > "$DIST/corunabus.jad" <<EOF
MIDlet-1: Coruna Bus, /icon.png, corunabus.BusMIDlet
MIDlet-Name: Coruna Bus
MIDlet-Vendor: Coruna Bus
MIDlet-Version: $VERSION
MIDlet-Permissions: javax.microedition.io.Connector.http
MIDlet-Permissions-Opt: javax.microedition.io.Connector.https,javax.microedition.location.Location
MIDlet-Jar-URL: corunabus.jar
MIDlet-Jar-Size: $JAR_SIZE
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
EOF

echo "OK: $DIST/corunabus.jar ($JAR_SIZE bytes) + $DIST/corunabus.jad"
