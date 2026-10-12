#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/app/build/tmp/kotlin-classes/debug"
SRC="$ROOT/tools/checks"
OUT="$ROOT/app/build/checks"

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
