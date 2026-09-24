#!/usr/bin/env bash
# キャッシュの形式の版（CacheFormat.VERSION）の上げ忘れを捕まえる検査。
#
#   bash test/cacheversion/run.sh            # 検査
#   bash test/cacheversion/run.sh --update   # 記録（facts.txt）を今の状態で書き直す
#   JCHE_CP="build/classes:依存jar..." bash test/cacheversion/run.sh   # コンパイル済みの classpath を使う
#
# キャッシュは「AST から読み取った事実」を持つ。事実の作り方（書き手）を変えたのに版を上げないと、
# 古いキャッシュが互換とみなされ、再利用したファイルだけ古い事実で解析される。壊れ方は
# 「エラー」ではなく「静かに違う結果」になる（docs/cache-split-qa.md の Q22 に実例がある）。
# 版を上げるかどうかを人の判断だけに任せず、「迷ったら上げる。上げ忘れは検査で捕まえる」ために置く。
#
# 決まった題材（test/demo・test/incremental・test/jls/project）を全件解析し、できたキャッシュの
# ブロック（F 行から次の F 行まで）の中身の指紋を facts.txt に記録しておく。次の場合に落とす。
#
#   事実の指紋が変わったのに、版・題材・環境（JDK・JDT）が記録と同じ
#       … 書き手の変更で事実が変わった。CacheFormat.VERSION を上げてから --update する
#   版が記録と違う
#       … 版を上げた。--update で記録を今の版に合わせる（上げたことが記録に残る）
#   題材が記録と違う
#       … 題材を変えると事実も変わるので、この検査では区別できない。書き手も変えたなら版も上げてから
#         --update する（題材の変更だけなら版は上げなくてよい）
#   環境（JDK のメジャー版・JDT の版）が記録と違う
#       … どちらもキャッシュの鍵（ヘッダ行）に入っていて、変われば古いキャッシュは自動で捨てられるので
#         版は上げなくてよい。--update で記録を合わせる
#
# 比べるのはブロックの中身だけ。ヘッダ行（版・環境）、L 行（依存 jar の絶対パスを含む）、T 行（題材の指紋）、
# 最終行は外す。ブロックはパスの順に並べ替えてから比べる（解析の順に依存させない）。
#
# 限界: 題材に現れない事実の変更は捕まえられない。題材と書き手を同じコミットで変えたときは
# 「題材が変わった」としか言えない（そのときは上の案内に従って自分で判断する）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
RECORD=facts.txt
UPDATE=no
[ "${1:-}" = "--update" ] && UPDATE=yes

if [ -n "${JCHE_CP:-}" ]; then
    CP="$JCHE_CP"
    JAVA_BIN=java
else
    JBANG="bash $ROOT/jbangw/jbang"
    CP=$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
    JAVA_HOME_25=$($JBANG jdk home 25)
    if [ -z "$CP" ] || [ -z "$JAVA_HOME_25" ]; then
        echo "  NG   jbang から JDT の classpath または JDK 25 を取得できませんでした"; echo "FAIL"; exit 1
    fi
    rm -rf build
    "$JAVA_HOME_25/bin/javac" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
        || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }
    CP="build:$CP"
    JAVA_BIN="$JAVA_HOME_25/bin/java"
fi

# 題材の指紋。題材のソースと、ここの設定ファイルの中身（パスつき）から作る
fixture_digest() {
    {
        for f in demo.properties incremental.properties jls.properties; do
            printf '%s\n' "$f"; cat "$f"
        done
        ( cd .. && find demo/src incremental/src jls/project/src -type f -name '*.java' | LC_ALL=C sort \
            | while read -r f; do printf '%s\n' "$f"; cat "$f"; done )
    } | sha256sum | cut -c1-16
}

# キャッシュのブロックだけを、パスの順に並べ替えて出す（ブロックの中の行の並びは保つ）
blocks_of() {
    for f in "$@"; do
        awk -F'\t' -v OFS='\t' '
            substr($0, 1, 1) == "F" { path = $2; n = 0 }
            path != "" && substr($0, 1, 1) != "Z" { printf "%s\t%08d\t%s\n", path, n++, $0 }
        ' "$f"
    done | LC_ALL=C sort -t "$(printf '\t')" -k1,1 -k2,2 | cut -f3-
}

rm -rf .cache out out.log
facts=""
for name in demo incremental jls; do
    if ! "$JAVA_BIN" -Dstdout.encoding=UTF-8 -cp "$CP" jche.CallHierarchyExporter "$name.properties" \
            > out.log 2>&1; then
        echo "  NG   題材 $name の解析に失敗しました"; tail -5 out.log; echo "FAIL"; exit 1
    fi
    caches=$(ls .cache/"$name"/*/*.tsv 2>/dev/null | LC_ALL=C sort)
    if [ -z "$caches" ]; then
        echo "  NG   題材 $name のキャッシュができていません"; echo "FAIL"; exit 1
    fi
    # shellcheck disable=SC2086
    facts="$facts$name $(blocks_of $caches | sha256sum | cut -c1-16)"$'\n'
done

version=$(grep -oE 'VERSION = "[^"]+"' "$ROOT/src/jche/cache/CacheFormat.java" | head -1 | cut -d'"' -f2)
header=$(head -1 "$(ls .cache/demo/*/analysis-cache.tsv | head -1)")
jdk=$(grep -oE 'jdk=[^[:space:]]+' <<< "$header" | head -1)
jdt=$(grep -oE 'jdt=[^[:space:]]+' <<< "$header" | head -1)

current=$(cat <<EOF
version $version
env $jdk $jdt
fixture $(fixture_digest)
$(printf '%s' "$facts" | sed 's/^/facts /')
EOF
)

if [ "$UPDATE" = yes ]; then
    printf '%s\n' "$current" > "$RECORD"
    echo "  OK   記録を更新しました（$RECORD）"
    cat "$RECORD"
    echo "PASS"; exit 0
fi

if [ ! -f "$RECORD" ]; then
    echo "  NG   記録（$RECORD）がありません。bash test/cacheversion/run.sh --update で作ってください"
    echo "FAIL"; exit 1
fi

recorded() { grep "^$1 " "$RECORD" | cut -d' ' -f2-; }
now() { grep "^$1 " <<< "$current" | cut -d' ' -f2-; }

if [ "$(now version)" != "$(recorded version)" ]; then
    echo "  NG   キャッシュの形式の版が記録と違います（記録: $(recorded version) / 今: $(now version)）"
    echo "       版を上げたなら、bash test/cacheversion/run.sh --update で記録を合わせてください"
    echo "FAIL"; exit 1
fi
if [ "$(now env)" != "$(recorded env)" ]; then
    echo "  NG   解析の環境が記録と違います（記録: $(recorded env) / 今: $(now env)）"
    echo "       JDK のメジャー版と JDT の版はキャッシュの鍵に入っているので、形式の版は上げなくて構いません。"
    echo "       bash test/cacheversion/run.sh --update で記録を合わせてください"
    echo "FAIL"; exit 1
fi
if [ "$(now fixture)" != "$(recorded fixture)" ]; then
    echo "  NG   題材（test/demo・test/incremental・test/jls/project のソースか、ここの設定）が記録と違います"
    echo "       題材だけを変えたなら版は上げずに --update してください。"
    echo "       同じ変更で事実の作り方（src/jche/analysis・src/jche/cache）も変えたなら、"
    echo "       CacheFormat.VERSION を上げてから --update してください（迷ったら上げる）"
    echo "FAIL"; exit 1
fi
if [ "$(grep '^facts ' <<< "$current")" != "$(grep '^facts ' "$RECORD")" ]; then
    echo "  NG   版を上げずにキャッシュの事実が変わりました（$(now version)）"
    diff <(grep '^facts ' "$RECORD") <(grep '^facts ' <<< "$current") | sed 's/^/       /'
    echo "       事実の作り方が変わると、古いキャッシュを再利用したファイルだけが古い事実のまま残ります。"
    echo "       src/jche/cache/CacheFormat.java の VERSION を上げてから"
    echo "       bash test/cacheversion/run.sh --update で記録を更新してください"
    echo "FAIL"; exit 1
fi
echo "  OK   事実の指紋が記録と一致（$(now version)）"
echo "PASS"
