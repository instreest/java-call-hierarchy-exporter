#!/usr/bin/env bash
# Eclipse プラグイン（eclipse-plugin/）の定義がそろっているかを検出する。
#
#   bash test/plugin/run.sh
#
# 見るのは「複数の場所に同じことを書いている箇所が食い違っていないか」だけ。
# ファイルの中身を読むだけなので、JDK も Maven も Eclipse もネットワークも要らない。
# 実際にビルドできるか（依存が解決でき、コンパイルが通るか）は GitHub Actions の
# eclipse-plugin ジョブが mvn package で確かめる。
#
# 検査項目
#   1) JDT の版が //DEPS 行・リポジトリ直下の pom.xml・eclipse-plugin/pom.xml で一致する
#   2) Bundle-Version（.qualifier を除く）と eclipse-plugin/pom.xml の version が一致する
#   3) Bundle-SymbolicName が plugin.xml とハンドラのコードで使う ID の前置きになっている
#   4) plugin.xml が指すクラス（ハンドラ・ビュー・Bundle-Activator）が実在する
#   5) plugin.xml のコマンド ID / ビュー ID が、キーバインドとソースの定数と食い違わない
#   6) build.properties の source.. に挙げたフォルダが実在する（src はリンクフォルダ）
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
PLUGIN=$ROOT/eclipse-plugin
ng=0
ok()  { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

# 1) JDT の版
deps_jdt=$(grep -E '^//DEPS ' "$ROOT/src/CallHierarchyExporter.java" \
    | tr ' ' '\n' | grep '^org.eclipse.jdt:org.eclipse.jdt.core:' | cut -d: -f3)
root_jdt=$(grep -A2 '<artifactId>org.eclipse.jdt.core</artifactId>' "$ROOT/pom.xml" \
    | grep '<version>' | head -1 | sed -E 's|.*<version>(.*)</version>.*|\1|')
plugin_jdt=$(grep '<jdt.version>' "$PLUGIN/pom.xml" | sed -E 's|.*<jdt.version>(.*)</jdt.version>.*|\1|')
echo "== JDT の版 =="
echo "  //DEPS=$deps_jdt  pom.xml=$root_jdt  eclipse-plugin/pom.xml=$plugin_jdt"
if [ -n "$deps_jdt" ] && [ "$deps_jdt" = "$root_jdt" ] && [ "$deps_jdt" = "$plugin_jdt" ]; then
    ok "3か所の JDT の版が一致する"
else
    fail "JDT の版が食い違っている。//DEPS 行に合わせること"
fi

# 2) バンドルの版
echo "== 版・ID =="
bundle_version=$(grep '^Bundle-Version:' "$PLUGIN/META-INF/MANIFEST.MF" | awk '{print $2}' | sed 's/\.qualifier$//')
pom_version=$(grep -m1 '<version>' "$PLUGIN/pom.xml" | sed -E 's|.*<version>(.*)</version>.*|\1|')
if [ -n "$bundle_version" ] && [ "$bundle_version" = "$pom_version" ]; then
    ok "Bundle-Version と pom.xml の version が一致する ($bundle_version)"
else
    fail "Bundle-Version=$bundle_version と pom.xml の version=$pom_version が食い違う"
fi

# 3) Bundle-SymbolicName
bsn=$(grep '^Bundle-SymbolicName:' "$PLUGIN/META-INF/MANIFEST.MF" | awk '{print $2}' | cut -d';' -f1)
if [ -n "$bsn" ] && grep -q "\"$bsn\"" "$PLUGIN/src-ui/jche/eclipse/ExportCallHierarchyHandler.java" \
   && grep -q "id=\"$bsn\." "$PLUGIN/plugin.xml"; then
    ok "Bundle-SymbolicName ($bsn) を plugin.xml とハンドラが同じ綴りで使っている"
else
    fail "Bundle-SymbolicName ($bsn) が plugin.xml かハンドラの綴りと食い違う"
fi

# 4) plugin.xml と MANIFEST.MF が指すクラスが実在するか
echo "== plugin.xml が指すクラス =="
classes=$(grep -oE '(defaultHandler|class)="[^"]+"' "$PLUGIN/plugin.xml" | sed -E 's|.*="(.*)"|\1|')
activator=$(grep '^Bundle-Activator:' "$PLUGIN/META-INF/MANIFEST.MF" | awk '{print $2}')
for cls in $classes $activator; do
    path=$PLUGIN/src-ui/$(echo "$cls" | tr '.' '/').java
    if [ -f "$path" ]; then
        ok "$cls"
    else
        fail "$cls のソースが無い ($path)"
    fi
done

# 5) コマンド ID・ビュー ID の突き合わせ
echo "== ID の突き合わせ =="
for cmd in $(grep -oE 'commandId="[^"]+"' "$PLUGIN/plugin.xml" | sed -E 's|commandId="(.*)"|\1|' | sort -u); do
    if grep -q "id=\"$cmd\"" "$PLUGIN/plugin.xml"; then
        ok "commandId $cmd に対応する <command> がある"
    else
        fail "commandId $cmd に対応する <command> の定義が無い（メニューもキーバインドも効かない）"
    fi
done
view_id=$(grep -A5 '<view$' "$PLUGIN/plugin.xml" | grep -oE 'id="[^"]+"' | head -1 | sed -E 's|id="(.*)"|\1|')
if [ -n "$view_id" ] && grep -q "VIEW_ID = \"$view_id\"" "$PLUGIN/src-ui/jche/eclipse/CallHierarchyView.java"; then
    ok "ビュー ID ($view_id) が plugin.xml とソースで一致する"
else
    fail "ビュー ID が plugin.xml ($view_id) とソースの VIEW_ID で食い違う。ビューを開けなくなる"
fi

# 6) build.properties の source フォルダ
echo "== build.properties =="
for dir in $(sed -n '/^source\.\. *=/,/[^\\]$/p' "$PLUGIN/build.properties" \
        | sed -E 's|^source\.\. *=||' | tr -d ' \\' | tr ',' '\n' | grep -v '^$'); do
    if [ -e "$PLUGIN/${dir%/}" ] || { [ "${dir%/}" = "src" ] && [ -d "$ROOT/src" ]; }; then
        ok "source.. の $dir がある"
    else
        fail "source.. の $dir が無い"
    fi
done
# リンクフォルダ src の実体がリポジトリ直下の src/ を指しているか
if grep -q 'PARENT-1-PROJECT_LOC/src' "$PLUGIN/.project"; then
    ok ".project のリンクフォルダ src がリポジトリ直下の src/ を指している"
else
    fail ".project にリンクフォルダ src の定義が無い。PDE が解析本体をコンパイルできない"
fi

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
