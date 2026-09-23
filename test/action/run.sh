#!/usr/bin/env bash
# GitHub Actions の複合アクション（.github/action/run.sh）が、run.log の依存 jar の警告を
# 表示言語に関わらず拾うことの検査。
#
#   bash test/action/run.sh
#
# アクションは run.log の [WARN] 行を書き出しの文字列で探して、warning アノテーションとジョブサマリに出す。
# 以前は日本語の書き出し（「依存jar:」）しか探しておらず、既定の英語のログでは警告が黙って消えていた。
# ここでは解析は動かさない（jbang をスタブに差し替える）。run.log に書く警告の文言は
# MessagesEn.java / MessagesJa.java の config.deps.missingJars からそのまま取るので、
# 文言の書き出しを変えてアクション側を直し忘れると落ちる。JDK もネットワークも要らない。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
export LANG=C.UTF-8

fail=0
ok() { echo "  OK   $1"; }
ng() { echo "  NG   $1"; fail=1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 解析の代わりに何もしないで成功する jbang
mkdir -p "$WORK/action/jbangw"
printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/action/jbangw/jbang"

# 文言の表から値を取る（{0} は件数に置き換える）
message() {
    grep -F "\"$2\", \"" "$ROOT/src/jche/util/$1" | head -1 | sed -E 's/^[^,]*, "//; s/",?[[:space:]]*$//; s/\{0\}/3/g'
}

: > "$WORK/dirs.txt"
for lang in En Ja; do
    msg=$(message "Messages$lang.java" config.deps.missingJars)
    repo=$(message "Messages$lang.java" config.repos.none)
    count=$(message "Messages$lang.java" exporter.classpathCount)
    if [ -z "$msg" ] || [ -z "$repo" ] || [ -z "$count" ]; then
        ng "Messages$lang.java から文言を取れない"
        continue
    fi
    d="$WORK/out-$lang"
    mkdir -p "$d"
    # 警告ではない依存 jar の行（件数の報告）は拾わないこと
    # ローカルリポジトリの警告も同じ書き出しでそろえてある（docs/output-files-simplify-qa.md の Q9）
    printf '[00:00.100s] %s\n[00:00.150s] [WARN] %s\n[00:00.200s] [WARN] %s\n' "$count" "$repo" "$msg" > "$d/run.log"
    echo "$d" >> "$WORK/dirs.txt"
done
echo "dummy.properties" > "$WORK/configs.txt"

out=$(GITHUB_ACTION_PATH="$WORK/action" JCHE_CONFIG_LIST="$WORK/configs.txt" \
      JCHE_OUTPUT_DIR_FILE="$WORK/dirs.txt" GITHUB_STEP_SUMMARY="$WORK/summary.md" \
      bash "$ROOT/.github/action/run.sh" 2>&1)
status=$?
[ "$status" -eq 0 ] && ok "終了コード 0" || ng "終了コード $status: $out"

for lang in En Ja; do
    msg=$(message "Messages$lang.java" config.deps.missingJars)
    repo=$(message "Messages$lang.java" config.repos.none)
    [ -n "$msg" ] || continue
    if printf '%s\n' "$out" | grep -qF "::warning title=依存jar::out-$lang: $msg"; then
        ok "$lang: warning アノテーションが出る"
    else
        ng "$lang: warning アノテーションが出ない"
    fi
    if printf '%s\n' "$out" | grep -qF "::warning title=依存jar::out-$lang: $repo"; then
        ok "$lang: ローカルリポジトリの警告も warning アノテーションが出る"
    else
        ng "$lang: ローカルリポジトリの警告が拾われない"
    fi
    if grep -qF -- "- $msg" "$WORK/summary.md" 2>/dev/null; then
        ok "$lang: ジョブサマリに載る"
    else
        ng "$lang: ジョブサマリに載らない"
    fi
done
n=$(printf '%s\n' "$out" | grep -c '^::warning')
[ "$n" -eq 4 ] && ok "警告ではない行は拾わない（warning は 4 件）" || ng "warning が $n 件（期待は 4 件）: $out"

if [ "$fail" -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
