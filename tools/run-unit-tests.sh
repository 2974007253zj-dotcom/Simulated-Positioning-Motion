#!/usr/bin/env bash
# 直接调用 JUnit 跑单元测试（绕过 Gradle Test 任务在 AGP 9 / Gradle 9.6.1 下的 classpath 问题）。
#
# 用法：
#   tools/run-unit-tests.sh                 # 跑 core-geo + core-data 的全部测试类
#   tools/run-unit-tests.sh CampusModeTest  # 只跑类名匹配的测试
#
# 前提：先编译出测试类（脚本会自动编译）：
#   JAVA_HOME=D:/AndroidBuild/jdk-21.0.2 ./gradlew --offline \
#     :core-geo:testClasses :core-data:compileScopedDebugUnitTestKotlin :xposed:compileScopedDebugUnitTestKotlin
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JDK="${TEST_JDK:-/d/AndroidBuild/jdk-17.0.2}"
JAVA="$JDK/bin/java"
[ -x "$JAVA" ] || { echo "找不到 Java: $JAVA（可用 TEST_JDK 指定）" >&2; exit 1; }

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
find_jar() { find "$CACHE" -name "$1" 2>/dev/null | head -1; }
w() { cygpath -w "$1"; }

JUNIT="$(find_jar 'junit-4.13.2.jar')"
HAM="$(find_jar 'hamcrest-core-1.3.jar')"
KSTD="$(find_jar 'kotlin-stdlib-2.2.10.jar')"
KCOR="$(find_jar 'kotlinx-coroutines-core-jvm-*.jar')"
OJSON="$(find "$CACHE/org.json" -name '*.jar' 2>/dev/null | head -1)"
AJAR="${ANDROID_JAR:-/d/AndroidBuild/sdk/platforms/android-36/android.jar}"

# org.json 必须排在 android.jar 之前：android.jar 里的 org.json 是抛 "Stub!" 的空壳
KSER="$(find_jar 'kotlinx-serialization-json-jvm-*.jar')"
[ -n "$KSER" ] || KSER="$(find_jar 'kotlinx-serialization-core-jvm-*.jar')"
KSERJ="$(find_jar 'kotlinx-serialization-json-jvm-*.jar')"
KSERC="$(find_jar 'kotlinx-serialization-core-jvm-*.jar')"

CP="$(w "$JUNIT");$(w "$HAM");$(w "$KSTD");$(w "$KCOR")"
[ -n "$KSERJ" ] && CP="$CP;$(w "$KSERJ")"
[ -n "$KSERC" ] && CP="$CP;$(w "$KSERC")"
[ -n "$OJSON" ] && CP="$CP;$(w "$OJSON")"
CP="$CP;$(w "$AJAR")"

add_classes() { [ -d "$1" ] && CP="$CP;$(w "$1")"; }

add_classes core-geo/build/classes/kotlin/main
add_classes core-geo/build/classes/kotlin/test
add_classes core-data/build/tmp/kotlin-classes/scopedDebug
add_classes core-data/build/tmp/kotlin-classes/scopedDebugUnitTest
add_classes core-data/build/intermediates/runtime_library_classes_dir/scopedDebug/bundleLibRuntimeToDirScopedDebug
add_classes xposed/build/tmp/kotlin-classes/scopedDebug
add_classes xposed/build/tmp/kotlin-classes/scopedDebugUnitTest
add_classes xposed/build/intermediates/runtime_library_classes_dir/scopedDebug/bundleLibRuntimeToDirScopedDebug

# 收集测试类（默认排除 Android 依赖重的数据库测试）
collect() {
  find core-geo/build/classes/kotlin/test core-data/build/tmp/kotlin-classes/scopedDebugUnitTest \
       xposed/build/tmp/kotlin-classes/scopedDebugUnitTest \
       -name '*Test.class' 2>/dev/null \
    | grep -v '\$' | grep -v 'Kt\.class$' \
    | sed "s|.*/kotlin/test/||; s|.*/kotlin-classes/scopedDebugUnitTest/||; s|/|.|g; s|\.class$||" \
    | grep -v 'LocationSpooferDataPackageTest' \
    | sort -u
}

if [ $# -gt 0 ]; then
  CLASSES="$(for p in "$@"; do
    find core-geo/build/classes/kotlin/test core-data/build/tmp/kotlin-classes/scopedDebugUnitTest \
         xposed/build/tmp/kotlin-classes/scopedDebugUnitTest \
         -name "*$p*.class" 2>/dev/null \
      | grep -v '\$' | grep -v 'Kt\.class$' \
      | sed "s|.*/kotlin/test/||; s|.*/kotlin-classes/scopedDebugUnitTest/||; s|/|.|g; s|\.class$||"
  done | sort -u)"
else
  CLASSES="$(collect)"
fi

[ -n "$CLASSES" ] || { echo "没有找到测试类，先编译：./gradlew --offline :core-geo:testClasses :core-data:compileScopedDebugUnitTestKotlin :xposed:compileScopedDebugUnitTestKotlin" >&2; exit 1; }

echo "运行：$(echo "$CLASSES" | wc -l) 个测试类"
# shellcheck disable=SC2086
exec "$JAVA" -cp "$CP" org.junit.runner.JUnitCore $CLASSES
