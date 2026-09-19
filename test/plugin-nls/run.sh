#!/usr/bin/env bash
# Eclipse プラグインの文言（英語が既定、日本語は重ねる）の検査。
#
#   bash test/plugin-nls/run.sh
#
# 文言をソースに直接書いていた頃は、日本語の Eclipse を使っていない人に読めなかった。
# Pleiades は Eclipse 本体の辞書しか持たないので、このプラグインの文言は訳されない
# （docs/eclipse-plugin-nls-qa.md）。いまは 2 か所に分けてある。
#
#   eclipse-plugin/src-ui/jche/eclipse/messages.properties     … コードが出す文言（英語・既定）
#   eclipse-plugin/src-ui/jche/eclipse/messages_ja.properties  … その日本語
#   eclipse-plugin/plugin.properties / plugin_ja.properties    … plugin.xml とバンドルの名前（OSGi が読む）
#
# 見ているのは次の6点。どれも「英語の利用者に日本語が出る」「画面にキー名が出る」という、
# 動かしてみるまで気づけない壊れ方である。
#   1) ソースに日本語の文字列リテラルが残っていないこと
#   2) 使っているキーが英語のファイルにあり、使っていないキーが残っていないこと
#   3) 英語と日本語でキーがそろい、差し込み（{0} {1} …）の数も同じこと
#   4) plugin.xml / MANIFEST.MF の %キー が両方のファイルにあること
#   5) 配布物（バンドル jar）に文言のファイルが入ること
#   6) 実際に読ませて、言語で切り替わり、UTF-8 として読めること
#
# JDK（9 以上。--release のため）が要る。Eclipse の jar は要らない。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
PLUGIN=$ROOT/eclipse-plugin
SRC=$PLUGIN/src-ui
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ng=0
ok()   { echo "  OK   $1"; }
fail() { echo "  NG   $1"; ng=$((ng + 1)); }

# properties からキーだけを取り出す（コメントと空行を捨てる）
keys_of() {
    grep -vE '^[[:space:]]*(#|$)' "$1" | sed -E 's/[[:space:]]*=.*//' | sed -E 's/[[:space:]]+$//' | sort
}

echo "== 1) ソースに日本語の文字列リテラルが残っていないこと =="
# コメント（// と /* */）を落としてから、文字列リテラルの中の仮名・漢字を探す。
# 1件でも残っていると、その行だけ英語の利用者に日本語が出る
LEFTOVER=$(find "$SRC" -name '*.java' -print0 | xargs -0 perl -0777 -ne '
    s{/\*.*?\*/}{}gs; s{//[^\n]*}{}g;
    while (/"(?:[^"\\]|\\.)*"/g) {
        my $lit = $&;
        print "$ARGV: $lit\n" if $lit =~ /[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}\x{ff01}-\x{ff60}]/;
    }' 2>/dev/null)
if [ -z "$LEFTOVER" ]; then
    ok "日本語の文字列リテラルは残っていない"
else
    fail "日本語の文字列リテラルが残っている（文言は messages.properties へ）"
    echo "$LEFTOVER" | sed 's/^/       /' | head -10
fi

echo "== 2) 使っているキーと、英語のファイルの中身が一致すること =="
EN=$SRC/jche/eclipse/messages.properties
JA=$SRC/jche/eclipse/messages_ja.properties
for f in "$EN" "$JA"; do
    [ -f "$f" ] || { fail "$f がありません"; echo "FAIL"; exit 1; }
done
# 使っているキーを集める。行をまたぐ呼び出しと、向きで出し分ける三項演算子
#   Messages.format(callers ? "a" : "b", 値)
# があるので行単位では拾えない。第1引数（最初のカンマまで）の中の文字列だけを見る
# （そうしないと Messages.format("x", last.field("methods")) の "methods" まで拾ってしまう）。
find "$SRC" -name '*.java' -print0 | xargs -0 perl -0777 -ne '
    while (/Messages\s*\.\s*(?:get|format)\s*\(/g) {
        my ($rest, $depth, $arg) = (substr($_, pos($_)), 0, "");
        while ($rest =~ /\G("(?:[^"\\]|\\.)*"|[(]|[)]|,|.)/gs) {
            my $t = $1;
            last if $depth == 0 && ($t eq "," || $t eq ")");
            $depth++ if $t eq "(";
            $depth-- if $t eq ")";
            $arg .= $t;
        }
        print "$1\n" while $arg =~ /"([a-zA-Z0-9._]+)"/g;
    }' | sort -u > "$WORK/used.txt"
keys_of "$EN" > "$WORK/en.txt"
keys_of "$JA" > "$WORK/ja.txt"

MISSING=$(comm -23 "$WORK/used.txt" "$WORK/en.txt")
if [ -z "$MISSING" ]; then
    ok "使っているキー $(wc -l < "$WORK/used.txt" | tr -d ' ') 件はすべて英語のファイルにある"
else
    fail "英語のファイルに無いキーを使っている（画面に !キー名! が出る）"
    echo "$MISSING" | sed 's/^/       /'
fi
UNUSED=$(comm -13 "$WORK/used.txt" "$WORK/en.txt")
if [ -z "$UNUSED" ]; then
    ok "使われていないキーは無い"
else
    fail "どこからも使われていないキーがある（消し忘れ）"
    echo "$UNUSED" | sed 's/^/       /'
fi

echo "== 3) 英語と日本語でキーと差し込みがそろうこと =="
ONLY_EN=$(comm -23 "$WORK/en.txt" "$WORK/ja.txt")
ONLY_JA=$(comm -13 "$WORK/en.txt" "$WORK/ja.txt")
if [ -z "$ONLY_EN" ] && [ -z "$ONLY_JA" ]; then
    ok "キーがそろっている（$(wc -l < "$WORK/en.txt" | tr -d ' ') 件）"
else
    [ -n "$ONLY_EN" ] && { fail "日本語だけ訳が無い"; echo "$ONLY_EN" | sed 's/^/       /'; }
    # 英語に無いキーは重ねても使われない（英語が土台なので）
    [ -n "$ONLY_JA" ] && { fail "日本語にしか無いキーがある（使われない）"; echo "$ONLY_JA" | sed 's/^/       /'; }
fi
# 差し込みの数が違うと、値が出ない・{0} がそのまま出る
placeholders() {
    grep -E "^$2[[:space:]]*=" "$1" | head -1 | grep -oE '\{[0-9]+\}' | sort -u | tr '\n' ' '
}
bad=0
while read -r key; do
    [ -z "$key" ] && continue
    if [ "$(placeholders "$EN" "$key")" != "$(placeholders "$JA" "$key")" ]; then
        fail "差し込みが食い違う: $key（英語 [$(placeholders "$EN" "$key")] / 日本語 [$(placeholders "$JA" "$key")]）"
        bad=$((bad + 1))
    fi
done < "$WORK/en.txt"
[ "$bad" -eq 0 ] && ok "差し込み（{0} {1} …）の数がそろっている"

echo "== 4) plugin.xml / MANIFEST.MF の %キー =="
grep -ohE '%[a-zA-Z][a-zA-Z0-9._]*' "$PLUGIN/plugin.xml" "$PLUGIN/META-INF/MANIFEST.MF" \
    | tr -d '%' | sort -u > "$WORK/pluginkeys.txt"
if [ ! -s "$WORK/pluginkeys.txt" ]; then
    fail "plugin.xml が %キー を使っていない（文言が訳されない）"
else
    for file in "$PLUGIN/plugin.properties" "$PLUGIN/plugin_ja.properties"; do
        [ -f "$file" ] || { fail "$(basename "$file") がありません"; continue; }
        keys_of "$file" > "$WORK/pk.txt"
        LACK=$(comm -23 "$WORK/pluginkeys.txt" "$WORK/pk.txt")
        if [ -z "$LACK" ]; then
            ok "$(basename "$file") に %キー $(wc -l < "$WORK/pluginkeys.txt" | tr -d ' ') 件がそろっている"
        else
            fail "$(basename "$file") に無い %キー がある（ビュー名などが %キー のまま出る）"
            echo "$LACK" | sed 's/^/       /'
        fi
    done
fi
grep -q '^Bundle-Localization: plugin$' "$PLUGIN/META-INF/MANIFEST.MF" \
    && ok "MANIFEST.MF の Bundle-Localization が plugin を指している" \
    || fail "MANIFEST.MF に Bundle-Localization: plugin が無い（%キー が解決されない）"

echo "== 5) 配布物の設定に文言のファイルが入っていること =="
grep -q 'plugin.properties' "$PLUGIN/build.properties" \
    && grep -q 'plugin_ja.properties' "$PLUGIN/build.properties" \
    && ok "build.properties（PDE）の bin.includes に入っている" \
    || fail "build.properties の bin.includes に plugin*.properties が無い"
grep -q '<include>plugin\*.properties</include>' "$PLUGIN/pom.xml" \
    && ok "pom.xml が plugin*.properties をバンドルに入れる" \
    || fail "pom.xml が plugin*.properties をバンドルに入れない"
grep -q '<include>\*\*/\*.properties</include>' "$PLUGIN/pom.xml" \
    && ok "pom.xml が src-ui の messages*.properties を拾う" \
    || fail "pom.xml が src-ui の *.properties を拾わない（画面にキー名が出る）"

echo "== 6) 実際に読ませる（言語の切り替えと UTF-8） =="
javac --release 11 -nowarn -d "$WORK/classes" -encoding UTF-8 \
    "$SRC/jche/eclipse/Messages.java" 2>"$WORK/javac.log"
if [ ! -f "$WORK/classes/jche/eclipse/Messages.class" ]; then
    fail "Messages を Java 11 でコンパイルできない"; sed 's/^/       /' "$WORK/javac.log" | head -10
else
    ok "Messages が Eclipse の jar 無し・Java 11 でコンパイルできる"
    cp "$EN" "$JA" "$WORK/classes/jche/eclipse/"
    cat > "$WORK/NlsProbe.java" <<'EOF'
import jche.eclipse.Messages;

/** 言語ごとの読み分けと、差し込み・UTF-8 の確認。出力は UTF-8 で書く */
public final class NlsProbe {
    public static void main(String[] args) throws Exception {
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        out.println(Messages.get("view.project"));
        out.println(Messages.format("state.stale", Integer.valueOf(12), "10:31:04"));
        out.println(Messages.get("no.such.key"));
    }
}
EOF
    javac --release 11 -nowarn -cp "$WORK/classes" -d "$WORK/classes" -encoding UTF-8 \
        "$WORK/NlsProbe.java" 2>/dev/null
    run() { java -Dosgi.nl="$1" -Duser.language="${2:-en}" -cp "$WORK/classes" NlsProbe 2>/dev/null; }

    EN_OUT=$(run en en)
    grep -qx 'Project:' <<<"$EN_OUT" && ok "既定（英語）で英語が出る" || fail "英語が出ない: $(head -1 <<<"$EN_OUT")"
    grep -q '12 file(s) have changed' <<<"$EN_OUT" && ok "英語で差し込みが効く" || fail "英語の差し込みが効かない"

    # Pleiades は Eclipse を日本語で動かす。-nl / eclipse.ini の指定は osgi.nl に入る
    JA_OUT=$(run ja en)
    grep -qx '対象プロジェクト:' <<<"$JA_OUT" \
        && ok "osgi.nl=ja（Pleiades）で日本語が出て、UTF-8 として正しく読める" \
        || fail "日本語にならない、または文字化けしている: $(head -1 <<<"$JA_OUT")"
    grep -q '12 ファイルが変更されています。表示は 10:31:04 時点' <<<"$JA_OUT" \
        && ok "日本語で差し込みが効く" || fail "日本語の差し込みが効かない"

    # osgi.nl が無い環境（Eclipse の外）では OS の言語に従う
    grep -qx '対象プロジェクト:' <<<"$(java -Duser.language=ja -cp "$WORK/classes" NlsProbe 2>/dev/null)" \
        && ok "osgi.nl が無ければ OS の言語に従う" || fail "OS の言語が効いていない"

    # 訳の無い言語は英語に落ちる（空白にならない）
    grep -qx 'Project:' <<<"$(run de en)" && ok "訳の無い言語は英語に落ちる" || fail "訳の無い言語で英語に落ちない"

    grep -qx '!no.such.key!' <<<"$EN_OUT" \
        && ok "無いキーは !キー名! として見える" || fail "無いキーの出方が違う"
fi

if [ "$ng" -eq 0 ]; then echo "PASS"; else echo "FAIL ($ng 件)"; exit 1; fi
