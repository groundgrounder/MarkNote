#!/usr/bin/env bash
#
# 跑 tools/checks/ 下的纯逻辑 JVM 断言。不需要模拟器，秒级完成。
#
# 为什么这些断言不放在 app/src/test 里：那个源集要引入 JUnit，而引入就得联网拉包；
# 而这里测的（差分边界、合并规则、双重上限、自救清栈）完全不需要 Android 运行时。
# 所以走「复用 Gradle 已编译的 class + javac 现场编译断言」，零新依赖。
#
# 前置：无（脚本自己会先把 class 编出来）
# 用法：tools/run_checks.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/app/build/tmp/kotlin-classes/debug"
SRC="$ROOT/tools/checks"
OUT="$ROOT/app/build/checks"

# 断言测的是 Kotlin 产物，先把它刷出来——否则测的是上一次编译的旧字节码。
# Gradle 的进度条会淹没断言输出，所以正常时只吞掉，失败时再整段打出来。
GRADLE_LOG="$(mktemp -t mn_checks_gradle)"
if ! (cd "$ROOT" && ./gradlew compileDebugKotlin --offline -q) >"$GRADLE_LOG" 2>&1; then
    printf 'compileDebugKotlin 失败：\n' >&2
    sed 's/^/    /' "$GRADLE_LOG" >&2
    printf '\n先修编译错误再跑断言。\n' >&2
    rm -f "$GRADLE_LOG"
    exit 1
fi
rm -f "$GRADLE_LOG"

if [ ! -d "$CLASSES" ]; then
    printf '找不到编译产物：%s\n' "$CLASSES" >&2
    exit 1
fi

# 断言只需要 stdlib。从 Gradle 缓存里挑版本最高的那个；有多个 Kotlin 版本共存时
# 可能与实际编译用的不一致，所以把选中的 jar 打出来，异常时好排查。
#
# 先在 kotlin 的窄目录里找（不到 1 秒）——直接 find 整个 caches 会扫 2.7G，
# 慢到会被系统杀掉。窄路径找不到时才退回全量搜索。
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
STDLIB=""
for dir in "$GRADLE_HOME/caches/modules-2/files-2.1/org.jetbrains.kotlin" "$GRADLE_HOME/caches"; do
    [ -d "$dir" ] || continue
    STDLIB="$(find "$dir" -name 'kotlin-stdlib-[0-9]*.jar' \
        -not -name '*-sources*' -not -name '*-javadoc*' 2>/dev/null | sort -V | tail -1)"
    [ -n "$STDLIB" ] && break
done
if [ -z "$STDLIB" ]; then
    printf '在 %s/caches 里找不到 kotlin-stdlib。\n先跑一次 ./gradlew assembleDebug 让它把依赖下下来。\n' \
        "$GRADLE_HOME" >&2
    exit 1
fi

mkdir -p "$OUT"
printf 'stdlib  : %s\n' "$(basename "$STDLIB")"
printf 'classes : %s\n\n' "$CLASSES"

total_pass=0
total_fail=0
broken=()

for src in "$SRC"/*.java; do
    name="$(basename "$src" .java)"
    log="$OUT/$name.javac.log"

    if ! javac -encoding UTF-8 -nowarn -cp "$CLASSES:$STDLIB" -d "$OUT" "$src" >"$log" 2>&1; then
        printf '%-18s 编译失败\n' "$name"
        sed 's/^/    /' "$log"
        broken+=("$name")
        continue
    fi

    out="$(java -Dfile.encoding=UTF-8 -cp "$CLASSES:$STDLIB:$OUT" "$name" 2>&1)"
    code=$?

    p="$(printf '%s' "$out" | grep -oE '通过 [0-9]+' | tail -1 | grep -oE '[0-9]+')"
    f="$(printf '%s' "$out" | grep -oE '失败 [0-9]+' | tail -1 | grep -oE '[0-9]+')"
    p="${p:-0}"; f="${f:-0}"

    total_pass=$((total_pass + p))
    total_fail=$((total_fail + f))

    printf '%-18s 通过 %-4s 失败 %s\n' "$name" "$p" "$f"

    # 失败的条目单独打出来——只看汇总数字不足以定位
    if [ "$f" != "0" ] || [ "$code" != "0" ]; then
        printf '%s' "$out" | grep -E 'FAIL' | sed 's/^/    /'
    fi
done

printf '\n合计：通过 %d / 失败 %d' "$total_pass" "$total_fail"
if [ ${#broken[@]} -gt 0 ]; then
    printf '，另有 %d 个文件编译失败（%s）' "${#broken[@]}" "${broken[*]}"
fi
printf '\n'

if [ "$total_fail" != "0" ] || [ ${#broken[@]} -gt 0 ]; then
    exit 1
fi
