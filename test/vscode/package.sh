#!/usr/bin/env bash
# VSCode プラグインの配布物（.vsix）が正しく組み立てられることの検査。
#
#   bash test/vscode/package.sh
#
# test/vscode/run.sh（Node だけで動く層の検査）とは分けてある。こちらは Maven で
# eclipse-plugin をビルドして lib/ を集めるので、要るものが多い（JDK 21 以上・Maven・Node・npm）。
#
# 検査項目
#   1) lib/ が集まる（jche-core.jar と JDT 一式 13 個）
#   2) 同梱する JDT の版が //DEPS 行と同じ（CLI と同じ JDT で解析するため）
#   3) .vsix に dist/extension.js・lib/・README・NOTICE・LICENSE が入り、
#      src/・node_modules/・検査は入っていない
#   4) .vsix の中の lib/ が eclipse-plugin の lib/ と同じ（出どころが1つであること）
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
PLUGIN=$ROOT/vscode-plugin
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
ng=0
ok()   { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

for tool in node npm mvn unzip; do
    command -v "$tool" >/dev/null || { echo "NG   $tool が要ります"; echo "FAIL"; exit 1; }
done

cd "$PLUGIN"
if [ ! -d node_modules ]; then
    echo "== 依存を入れる（npm ci）"
    npm ci --no-audit --no-fund --loglevel=error || { echo "FAIL"; exit 1; }
fi

echo "== lib/ を集める =="
bash scripts/collect-lib.sh --reuse >"$WORK/lib.log" 2>&1 || { sed 's/^/       /' "$WORK/lib.log"; echo "FAIL"; exit 1; }
[ -f lib/jche-core.jar ] && ok "lib/jche-core.jar" || fail "lib/jche-core.jar が無い"
JDT_COUNT=$(ls lib/jdt/*.jar 2>/dev/null | wc -l)
[ "$JDT_COUNT" -eq 13 ] && ok "lib/jdt/ に JDT 一式 13 個" || fail "lib/jdt/ の jar が 13 個でない（$JDT_COUNT）"

deps_jdt=$(grep -E '^//DEPS ' "$ROOT/src/jche/CallHierarchyExporter.java" \
    | tr ' ' '\n' | grep '^org.eclipse.jdt:org.eclipse.jdt.core:' | cut -d: -f3)
if ls "lib/jdt/org.eclipse.jdt.core-$deps_jdt.jar" >/dev/null 2>&1; then
    ok "同梱する JDT の版が //DEPS と同じ ($deps_jdt)"
else
    fail "同梱する JDT の版が //DEPS ($deps_jdt) と違う: $(ls lib/jdt/org.eclipse.jdt.core-*.jar 2>/dev/null)"
fi

echo "== .vsix を作る =="
rm -f call-hierarchy-exporter.vsix
npm run package --silent >"$WORK/package.log" 2>&1 || { sed 's/^/       /' "$WORK/package.log"; echo "FAIL"; exit 1; }
[ -s call-hierarchy-exporter.vsix ] && ok ".vsix ができた（$(du -h call-hierarchy-exporter.vsix | cut -f1)）" || fail ".vsix ができていない"

unzip -Z1 call-hierarchy-exporter.vsix > "$WORK/entries"
# vsce は README.md を readme.md に、LICENSE を LICENSE.txt に改名して入れる
for must in extension/dist/extension.js extension/lib/jche-core.jar extension/readme.md extension/NOTICE extension/LICENSE.txt extension/package.json; do
    grep -qx "$must" "$WORK/entries" && ok "入っている: $must" || fail "入っていない: $must"
done
VSIX_JDT=$(grep -c '^extension/lib/jdt/.*\.jar$' "$WORK/entries")
[ "$VSIX_JDT" -eq 13 ] && ok "入っている: lib/jdt/*.jar 13 個" || fail "lib/jdt/ の jar が 13 個でない（$VSIX_JDT）"
for mustnot in '^extension/src/' '^extension/node_modules/' '^extension/test/' '^extension/out/' '^extension/scripts/'; do
    if grep -qE "$mustnot" "$WORK/entries"; then fail "入ってはいけない: $mustnot"; else ok "入っていない: $mustnot"; fi
done

# 4) 出どころが1つ。eclipse-plugin の lib/ と同じ名前・同じ中身
diff <(cd "$ROOT/eclipse-plugin/target/classes/lib" && find . -type f | sort | xargs sha256sum) \
     <(cd lib && find . -type f | sort | xargs sha256sum) >"$WORK/diff" \
    && ok "lib/ が eclipse-plugin の同梱物と同一" || { fail "lib/ が eclipse-plugin の同梱物と違う"; sed 's/^/       /' "$WORK/diff"; }

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
