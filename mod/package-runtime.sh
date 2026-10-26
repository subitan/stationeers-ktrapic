#!/usr/bin/env bash
# Builds the trimmed Java runtime the plugin downloads to run the compile daemon on, as a zip whose
# root is the runtime (bin/, lib/, ...), for the platform this runs on.
#
# Usage: mod/package-runtime.sh <output.zip>   (with JAVA_HOME a JDK 21 that has jmods/)
#
# The modules are what `jdeps --print-module-deps` finds in the daemon jar, plus ones the Kotlin
# compiler loads by reflection. mod/tests runs the daemon on this runtime in CI, which catches a
# missing module.
set -euo pipefail

out=$(realpath -m "$1")
java_home=${JAVA_HOME:?set JAVA_HOME to a JDK 21}
modules=java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.rmi,java.scripting,java.sql,java.xml,jdk.compiler,jdk.unsupported,jdk.zipfs

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$java_home/bin/jlink" \
  --module-path "$java_home/jmods" \
  --add-modules "$modules" \
  --strip-debug --no-header-files --no-man-pages --compress zip-9 \
  --output "$work/runtime"
rm -f "$out"
"$java_home/bin/jar" --create --no-manifest --file "$out" -C "$work/runtime" .
echo "Wrote $out"
