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
#   1) JDT の版の関係（下限でコンパイルしているか、下限が //DEPS 以下か）
#   2) Bundle-Version（.qualifier を除く）と eclipse-plugin/pom.xml の version が一致する
#   3) Bundle-SymbolicName が plugin.xml とハンドラのコードで使う ID の前置きになっている
#   4) plugin.xml が指すクラス（ハンドラ・ビュー・Bundle-Activator）が実在する
#   5) plugin.xml のコマンド ID / ビュー ID が、キーバインドとソースの定数と食い違わない
#   6) build.properties の source.. に挙げたフォルダが実在する（src はリンクフォルダ）
#   7) ツールバーのアイコン（ViewIcons の文字盤）が 16 行 × 16 文字で、決めた文字しか使っていない
#   8) キャッシュのフォルダ名（.cache）が、プラグイン側と解析本体で同じ
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
PLUGIN=$ROOT/eclipse-plugin
ng=0
ok()  { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

# 1) JDT の版。2か所あって役割が違う
#    eclipse-plugin/pom.xml … バンドルに同梱して子プロセスで使う版。//DEPS 行と同じであること
#    MANIFEST.MF の bundle-version … Eclipse 側に要求する下限（モデル API 用）。同梱版以下であること
deps_jdt=$(grep -E '^//DEPS ' "$ROOT/src/jche/CallHierarchyExporter.java" \
    | tr ' ' '\n' | grep '^org.eclipse.jdt:org.eclipse.jdt.core:' | cut -d: -f3)
bundled_jdt=$(grep '<jdt.version>' "$PLUGIN/pom.xml" | sed -E 's|.*<jdt.version>(.*)</jdt.version>.*|\1|')
floor_jdt=$(grep 'org.eclipse.jdt.core;bundle-version=' "$PLUGIN/META-INF/MANIFEST.MF" \
    | sed -E 's|.*bundle-version="([^"]+)".*|\1|')
echo "== JDT の版 =="
echo "  //DEPS=$deps_jdt  同梱=$bundled_jdt  Eclipse側の下限=$floor_jdt"
if [ -z "$deps_jdt" ] || [ -z "$bundled_jdt" ] || [ -z "$floor_jdt" ]; then
    fail "どれかの版が取り出せない"
elif [ "$bundled_jdt" != "$deps_jdt" ]; then
    fail "同梱する版 ($bundled_jdt) が //DEPS の版 ($deps_jdt) と違う。CLI と別の JDT で解析することになる"
elif [ "$(printf '%s\n%s\n' "$floor_jdt" "$bundled_jdt" | sort -V | head -1)" != "$floor_jdt" ]; then
    fail "Eclipse 側の下限 ($floor_jdt) が同梱版 ($bundled_jdt) より新しい"
else
    ok "同梱する版が //DEPS と一致し、Eclipse 側の下限 ($floor_jdt) はそれ以下である"
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
if [ -n "$bsn" ] && grep -q "PLUGIN_ID = \"$bsn\"" "$PLUGIN/src-ui/jche/eclipse/JchePlugin.java" \
   && grep -q "id=\"$bsn\." "$PLUGIN/plugin.xml"; then
    ok "Bundle-SymbolicName ($bsn) を plugin.xml とソースの PLUGIN_ID が同じ綴りで使っている"
else
    fail "Bundle-SymbolicName ($bsn) が plugin.xml かソースの PLUGIN_ID と食い違う"
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

# 4.5) 解析本体をバンドルのクラスパスに載せていないこと
echo "== 解析本体の隔離 =="
if grep -q '^Bundle-ClassPath' "$PLUGIN/META-INF/MANIFEST.MF"; then
    fail "Bundle-ClassPath がある。lib/ を載せると Eclipse（古い JDK かもしれない）が解析本体を読んでしまう"
else
    ok "Bundle-ClassPath は無い（lib/ は子プロセスの -cp にだけ渡す）"
fi
# import だけでなく、完全修飾名での参照も見る（import が無くても依存は依存）。
# コメント行（* や // で始まる行）は説明なので除く
leaks=$(grep -rhE 'jche\.(analysis|cache|config|graph|report|external|extension|server|util)\.[A-Za-z]+' "$PLUGIN/src-ui" \
    | grep -vE '^\s*(\*|//|/\*)' \
    | grep -oE 'jche\.(analysis|cache|config|graph|report|external|extension|server|util)\.[A-Za-z]+' \
    | sort -u)
if [ -n "$leaks" ]; then
    fail "プラグインが解析本体を直接参照している（別プロセスにした意味が無くなる）:"
    echo "$leaks" | sed 's/^/       /'
else
    ok "プラグインは解析本体（jche.* のうち jche.eclipse 以外）を参照していない"
fi
if grep -q 'copy-dependencies' "$PLUGIN/pom.xml" && grep -q 'core-jar' "$PLUGIN/pom.xml"; then
    ok "ビルドが lib/jche-core.jar と lib/jdt/ を作る設定になっている"
else
    fail "lib/ を作るビルド設定が無い"
fi

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
# 設定ページの ID。ビューの［▽］メニューからこの ID を指して設定ページを開くので、
# 食い違うと「設定（JDK・置き場所）…」が空のダイアログになる
page_id=$(grep -A4 '<page$' "$PLUGIN/plugin.xml" | grep -oE 'id="[^"]+"' | head -1 | sed -E 's|id="(.*)"|\1|')
if [ -n "$page_id" ] \
        && grep -q "PREFERENCE_PAGE_ID = \"$page_id\"" "$PLUGIN/src-ui/jche/eclipse/CallHierarchyView.java"; then
    ok "設定ページ ID ($page_id) が plugin.xml とソースで一致する"
else
    fail "設定ページ ID が plugin.xml ($page_id) とソースの PREFERENCE_PAGE_ID で食い違う。ビューから設定を開けない"
fi

# 6) build.properties と PDE の構成
echo "== build.properties =="
for dir in $(grep -E '^source\.\. *=' "$PLUGIN/build.properties" \
        | sed -E 's|^source\.\. *=||' | tr -d ' \\' | tr ',' '\n' | grep -v '^$'); do
    if [ -e "$PLUGIN/${dir%/}" ]; then
        ok "source.. の $dir がある"
    else
        fail "source.. の $dir が無い"
    fi
done
if grep -qE '^source\.\. *=.*\bsrc/' "$PLUGIN/build.properties"; then
    fail "build.properties が解析本体（src/）もコンパイル対象にしている。"\
"プラグインは Java 11、解析本体は Java 17 なので混ぜられない"
else
    ok "PDE がコンパイルするのは src-ui/ だけ（解析本体は lib/jche-core.jar として同梱）"
fi
if grep -q 'lib/' "$PLUGIN/build.properties"; then
    ok "bin.includes に lib/ が入っている（同梱物がバンドルに含まれる）"
else
    fail "bin.includes に lib/ が無い。PDE でエクスポートすると解析本体が入らない"
fi
if grep -q 'JavaSE-11' "$PLUGIN/META-INF/MANIFEST.MF"; then
    ok "Bundle-RequiredExecutionEnvironment が JavaSE-11（下限は Eclipse 4.17 / 2020-09）"
else
    fail "BREE が JavaSE-11 ではない（下限を変えたなら test/plugin-api も揃えること）"
fi

# 7) ツールバーのアイコン。絵を文字盤で持っているので、大きさと文字をここで検査できる
#    （画像ファイルなら中身を見られないが、文字盤なら「16×16 か」「知らない文字が無いか」が分かる。
#     ずれたまま動かすと、アイコンが欠けるか、SWT が例外を投げる）
echo "== ツールバーのアイコン（ViewIcons） =="
ICONS=$PLUGIN/src-ui/jche/eclipse/ViewIcons.java
if [ ! -f "$ICONS" ]; then
    fail "ViewIcons.java が無い"
else
    rows=$(grep -oE '^ +"[.agw]*",$' "$ICONS" | sed -E 's|^ +"(.*)",$|\1|')
    total=$(printf '%s\n' "$rows" | grep -c . )
    bad=$(printf '%s\n' "$rows" | awk '{ if (length($0) != 16) print NR": "length($0) }')
    maps=$(grep -cE '^ +static final String\[\] [A-Z_]+ = \{$' "$ICONS")
    if [ -n "$bad" ]; then
        fail "16 文字でない行がある: $(echo "$bad" | tr '\n' ' ')"
    elif [ "$maps" -eq 0 ] || [ "$total" -ne $((maps * 16)) ]; then
        fail "文字盤 $maps 枚に対して行数が $total（16 行 × 枚数であること）"
    else
        ok "アイコン $maps 枚が 16 行 × 16 文字（使う文字は . a g w のみ）"
    fi
fi

# 8) キャッシュのフォルダ名。プラグインは解析本体のクラスを参照できない（別プロセス・別の Java の版）ので、
#    同じ名前を 2 か所に書いている。食い違うと、設定画面の［解析キャッシュを削除］が
#    「消すキャッシュはありません」と言って何も消さない（利用者には理由が分からない）
echo "== キャッシュのフォルダ名 =="
core_cache=$(grep -oE 'DEFAULT_CACHE_DIR_NAME = "[^"]+"' "$ROOT/src/jche/config/Config.java" \
    | head -1 | sed -E 's|.*"(.*)"|\1|')
plugin_cache=$(grep -oE 'CACHE_DIR_NAME = "[^"]+"' "$PLUGIN/src-ui/jche/eclipse/PluginFolders.java" \
    | head -1 | sed -E 's|.*"(.*)"|\1|')
if [ -z "$core_cache" ] || [ -z "$plugin_cache" ]; then
    fail "キャッシュのフォルダ名を取り出せない（解析本体=$core_cache / プラグイン=$plugin_cache）"
elif [ "$core_cache" != "$plugin_cache" ]; then
    fail "キャッシュのフォルダ名が食い違う（解析本体=$core_cache / プラグイン=$plugin_cache）"
else
    ok "キャッシュのフォルダ名が一致する（$core_cache）"
fi

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
