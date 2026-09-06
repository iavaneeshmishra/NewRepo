#!/usr/bin/env bash
# Compiles the platform-independent Kotlin core + its unit tests with a bare
# kotlinc (no Gradle, no Android SDK) and runs them against protocol/test-vectors.json.
#
#   KOTLINC=/path/to/kotlinc JAVA_HOME=/path/to/jdk tools/conformance/run-kotlin.sh
#
# The normal way to run these tests is `./gradlew testDebugUnitTest` in android/;
# this script exists so the core can be verified on machines without the SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-/tmp/ripple-kotlin-conformance}"
rm -rf "$OUT" && mkdir -p "$OUT"

"$KOTLINC" -jvm-target 17 -nowarn -d "$OUT" \
  "$ROOT"/android/app/src/main/java/app/ripple/mesh/core/*.kt \
  "$ROOT"/android/app/src/test/java/app/ripple/mesh/core/*.kt \
  "$ROOT"/tools/conformance/kotlin/shims/*.kt \
  "$ROOT"/tools/conformance/kotlin/Runner.kt

cp "$ROOT/protocol/test-vectors.json" "$OUT/test-vectors.json"
STDLIB="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT:$STDLIB" conformance.RunnerKt \
  app.ripple.mesh.core.ProtocolVectorsTest app.ripple.mesh.core.MeshRouterTest
