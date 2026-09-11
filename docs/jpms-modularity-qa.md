# JPMS（Java Platform Module System）によるモジュール化の検討 — QA 一覧

「このツールを JPMS（`module-info.java`）でモジュール化できるか、すべきか」を検討した記録。
迷ったこと・調べたこと・その結論を Q&A の形で残す。

結論の要点:

- **今は見送る。** 依存する JDT Core の jar 群に**分割パッケージ**（同じパッケージが複数の jar にまたがる）が
  あり、依存を module-path に載せた時点で `javac` / `java` が拒否する。これは JDT（Eclipse）側の
  配布形態（OSGi バンドル）に由来するもので、このツール側の工夫では解消できない（Q3・Q4）
- 分割を避けるには依存 jar を自前で合成・再配布するしかなく、「`//DEPS` 1 行で Maven Central から取ってきて
  ソースのまま動く」「Pleiades の plugins から jar を集めて動かす」という本ツールの配布の流儀と釣り合わない（Q5）
- モジュール化で得たかったもの（パッケージ間の依存の規律、拡張に見せる API の限定）は、
  JPMS 無しでも `src/jche` のパッケージ依存が既に無循環であることと、拡張が触るのが `jche.extension` だけである
  ことで大半が満たせている。残りは CI の軽い検査で代替できる（Q7・Q8）
- JDT 側が `module-info` 付きで配布されるようになったら再検討する。そのときの手順も書いておく（Q9）
- 「jche だけモジュール化し、JDT は classpath の無名モジュールのまま」という案も試した。`--add-reads` を使えば
  `java` 直叩きでは動くが、JBang の `--module` は `//DEPS` を必ず module-path に置くので本流では成立しない（Q10〜Q13）

---

## 前提

### Q1. 今の構成はどうなっているか

- ソースは `src/CallHierarchyExporter.java`（解析の入口）、`src/Jche.java`（対話メニュー）と、
  `src/jche/*` の 11 パッケージ（約 15,000 行）。JBang の `//SOURCES jche/**/*.java` で一緒にコンパイルされる
- 依存は `//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0` の 1 つだけ。推移的に
  `org.eclipse.platform.*`（core.runtime / equinox.* / core.resources 等）、`ecj`、`jna`、`org.osgi.*` の
  計 19 jar が classpath に載る
- 実行は常に **classpath**（無名モジュール）。JBang、`pom.xml`（Eclipse 用）、README の Pleiades 手順
  （`javac -classpath lib\*`）、`.github/workflows/smoke.yml` のいずれも module-path は使っていない
- `plugin.folders` の拡張は `URLClassLoader`（親＝ツール自身のクラスローダ）で読む
  （[PluginClassLoaders](../src/jche/config/PluginClassLoaders.java)）。拡張は `jche.extension.*` と
  JDT の AST（`org.eclipse.jdt.core.dom`）を触る

### Q2. `src/jche` のパッケージ間の依存はどうなっているか

`import jche.*` を集計した（2026-09 時点）。

| パッケージ | 依存先（jche 内） |
| --- | --- |
| `util` | （なし） |
| `cache` | （なし） |
| `extension` | （なし） |
| `framework` | cache |
| `config` | extension, util |
| `builtin` | extension, util |
| `cli` | config |
| `graph` | cache, config, extension, framework, util |
| `analysis` | cache, config, extension, util |
| `report` | cache, config, framework, graph, util |
| `external` | cache, config, graph, report, util |

**循環は無い。** `util` / `cache` / `extension` が最下層、`external` と `report` が最上層で、
きれいな層になっている。つまり「モジュール化して初めて依存の乱れが見つかる」状態ではない。

---

## 技術的な可否

### Q3. 依存 jar は JPMS でどう見えるか

19 jar のうち `module-info.class` を持つのは `org.eclipse.osgi` だけ。残りはすべて自動モジュール
（`Automatic-Module-Name` がマニフェストにある。`osgi.annotation` だけは無く、ファイル名から名前が導かれる）。
自動モジュールなので「module-path に載せて `requires org.eclipse.jdt.core;` と書く」こと自体はできる、
はずだった。

### Q4. 何が壁になったか — 分割パッケージ

各 jar の `.class` のパッケージを突き合わせると、次のパッケージが複数の jar にまたがっていた。

| パッケージ | 入っている jar |
| --- | --- |
| `org.eclipse.jdt.core` | `org.eclipse.jdt.core`, `ecj` |
| `org.eclipse.jdt.core.compiler` | `org.eclipse.jdt.core`, `ecj` |
| `org.eclipse.jdt.internal.compiler` | `org.eclipse.jdt.core`, `ecj` |
| `org.eclipse.jdt.internal.compiler.parser` | `org.eclipse.jdt.core`, `ecj` |
| `org.eclipse.core.runtime` | `org.eclipse.core.runtime`, `org.eclipse.equinox.common`, `org.eclipse.equinox.registry` |
| `org.eclipse.core.internal.runtime` | `org.eclipse.core.runtime`, `org.eclipse.equinox.common` |

JPMS は「1 つのパッケージは 1 つのモジュールにしか属せない」ので、これらは同時に module-path に載せられない。
OSGi では `Export-Package` の単位が細かく、同名パッケージを複数バンドルが分担して持つのは普通のことなので、
Eclipse 由来の jar にはこの形が多い。

実際に試した結果（JDK 21、19 jar をすべて module-path に置き、`requires org.eclipse.jdt.core;` だけの
`module-info.java` をコンパイル）:

```
error: the unnamed module reads package org.eclipse.core.runtime from both org.eclipse.core.runtime and org.eclipse.equinox.common
error: the unnamed module reads package org.eclipse.jdt.core from both org.eclipse.jdt.core.compiler.batch and org.eclipse.jdt.core
error: module com.sun.jna.platform reads package org.eclipse.jdt.internal.compiler from both org.eclipse.jdt.core and org.eclipse.jdt.core.compiler.batch
（同種のエラーが 20 件以上）
```

「`org.eclipse.jdt.core` だけを module-path、残りを classpath」も試した。コンパイルは通るが実行時に
`NoClassDefFoundError: org/eclipse/jdt/core/compiler/CharOperation` で落ちる。
`org.eclipse.jdt.core.compiler` パッケージは名前付きモジュール `org.eclipse.jdt.core` のものと決まるので、
classpath 側（`ecj`）にある同パッケージのクラスが見えなくなるため。
このツールが使うのは `org.eclipse.jdt.core.dom` が中心だが、その内部が ecj のクラスを呼ぶので避けられない。

逆に「ツールを名前付きモジュール、依存はすべて classpath」は、名前付きモジュールが無名モジュールを
`requires` できないので `module not found: org.eclipse.jdt.core` で最初から通らない。

### Q5. 分割パッケージを回避する手はあるか

あるにはあるが、どれも配布の流儀と釣り合わない。

| 案 | 内容 | 採否 |
| --- | --- | --- |
| A | `org.eclipse.jdt.core` と `ecj` を 1 つの jar に合成し、core.runtime 系も同様にまとめて、自前の Maven 座標で配る | 不採用。合成 jar の版管理・再配布・ライセンス表記を抱え込む。`//DEPS` 1 行と Pleiades 手順（`plugins\*.jar` を集めるだけ）の両方が成り立たなくなる |
| B | `--patch-module org.eclipse.jdt.core=ecj.jar` で実行時に継ぎ足す | 不採用。JBang の `//RUNTIME_OPTIONS` で書けるが、`core.runtime` 側の 3 jar も同様に要り、かつ `--patch-module` はテスト・デバッグ向けの仕組みで、本番の起動に常用するものではない。JDT の版を上げるたびに継ぎ足す組み合わせを見直す必要がある |
| C | `jdeps --generate-module-info` で `module-info` を作って jar に注入する | 不採用。分割そのものは解消しないので A と同じ合成が要る |
| D | JDT を使うのをやめる | 論外。解析の本体が JDT |

### Q6. JBang 自体はモジュールに対応しているか

対応している（同梱の 0.141.0 に `--module` オプションがある。`//MODULE` 指示行も使える）。
なので JBang が障害なのではなく、Q4 の依存側が障害。

---

## 目的から見た判断

### Q7. モジュール化で何を得たかったのか。それは他の手段で満たせるか

| 得たいもの | JPMS 無しで満たせるか |
| --- | --- |
| パッケージ間の依存を一方向に保つ（層の規律） | **既に満たしている**（Q2）。壊れないよう CI で検査する（Q8） |
| 拡張（`plugin.folders`）に見せる API を `jche.extension` に限定する | 拡張のクラスパスはツール全体なので技術的には何でも触れる。ただし拡張の契約は `jche.extension` の 5 型だけと文書化しており、`builtin` の 2 実装も同じ型しか使っていない。強制する必要が今のところ無い |
| 不要な依存を切り離して起動を軽くする | JDT の自動モジュールは全パッケージを export するので、モジュール化しても切れない |
| `jlink` で JDK 込みの配布物を作る | 自動モジュールは `jlink` に使えないので、モジュール化しても不可。配布物は JBang が JDK ごと取ってくる方式で足りている |
| 内部クラスへの外部からの参照を禁じる（強いカプセル化） | 利用者は CSV を受け取るだけで、ライブラリとしての利用を想定していない（README のとおり）。守るべき「公開 API」がそもそも無い |

つまり、JPMS で得られるもののうち、このツールにとって価値があるのは「依存の規律」だけで、
それは既に満たしていて、維持は軽い検査で足りる。

### Q8. 依存の規律を保つ検査は要るか。どうするか

必要になったら足す。JPMS 抜きで最も軽い方法は、`smoke.yml` の javac の後に
「`import jche.X` の集計が許可リストの範囲に収まっているか」を `grep` で見る 1 ステップを置くこと
（Q2 の表をそのまま許可リストにする）。ArchUnit のような依存を足すほどの規模ではない。
現時点で規律は守れているので、この文書には方針だけ残し、実装は乱れが出たときにする。

### Q9. 再検討する条件と、そのときの手順

**条件:** `org.eclipse.jdt.core` が分割パッケージの無い形（`ecj` を含む単一 jar、または `module-info` 付き）で
Maven Central に出ること。Eclipse 側では JDT の `module-info` 化の議論はあるが、OSGi との両立が必要で、
すぐには来ない見込み。JDT の版を上げるとき（`//DEPS` の書き換え時）に、次で再確認する。

```bash
# 依存 jar を集めて分割パッケージを探す（重複が出なければ Q4 の壁は消えている）
CP=$(bash jbangw/jbang info classpath src/CallHierarchyExporter.java | tail -1 | tr ':' '\n' | grep -v '/cache/jars/')
for j in $CP; do unzip -Z1 "$j" | grep '\.class$' | grep -v META-INF | sed 's#/[^/]*$##' | sort -u | sed "s#\$# $(basename $j)#"; done \
  | sort | awk '{p[$1]=p[$1]" "$2; c[$1]++} END {for (k in c) if (c[k]>1) print k, p[k]}'
```

**壁が消えたときの形（案）:**

- モジュールは **1 つ**（`io.github.instreest.jche`）。`src/jche` の 11 パッケージは層になっているが、
  15,000 行を複数モジュールに分ける必然は無く、JBang の `//SOURCES` 1 本で動く今の形も保てる
- `exports jche.extension;` だけを公開し、`builtin` は `exports jche.builtin;`（設定に FQN を書いて
  `Class.forName` するため）。それ以外は非公開
- `requires org.eclipse.jdt.core;` `requires java.compiler;`（拡張の実行時コンパイル）
- 拡張（`plugin.folders`）は `URLClassLoader` 経由の無名モジュールのままでよい。無名モジュールは
  すべての名前付きモジュールを読めるので、`jche.extension` と JDT に届く
- `Class.forName(名前, true, loader)` で読む拡張のインスタンス化は `newInstance` を使うので、
  拡張側のパッケージが開いている必要は無い（無名モジュールは全パッケージが open）
- README の Pleiades 手順は `-classpath` のまま動かせる（名前付きモジュールを classpath に置けば
  無名モジュールとして扱われる）ので、そちらは変えなくてよい

---

## 追加検討: jche だけをモジュール化し、JDT は classpath の無名モジュールのままにする案

### Q10. 名前付きモジュールから classpath の JDT を読めるか

原則は「名前付きモジュールは無名モジュールを `requires` できない」（Q4 の末尾）だが、
`--add-reads jche=ALL-UNNAMED` を **javac と java の両方**に付ければ読める。
これを前提に、`src/jche` 全体を 1 モジュールにして試した（JDK 21、`--release 21`）。

結果: **`java` / `javac` を直接使う経路では動く。JBang の本流では動かない。**

### Q11. javac / java 直叩きで何が必要だったか

`module-info.java`（`requires java.compiler; requires java.xml; exports jche.extension; exports jche.builtin;`）を
置いたうえで、次の変更が必要になった。

| 必要な変更 | 理由 |
| --- | --- |
| `src/CallHierarchyExporter.java` と `src/Jche.java` をパッケージに入れる（例: `jche.app`） | 名前付きモジュールに無名パッケージは置けない（`unnamed package is not allowed in named modules`）。README の `jbang src/CallHierarchyExporter.java`、起動スクリプト、`test/pom/run.sh`、Pleiades 手順、`smoke.yml` の全部が影響を受ける |
| `requires java.xml;` を足す | `jche.config` の pom 解析が `org.w3c.dom` / `javax.xml.parsers` を使っている。classpath 時代は暗黙に見えていた |
| `--add-reads jche=ALL-UNNAMED` をコンパイルと実行の両方に付ける | 付け忘れると実行時に `IllegalAccessError: module jche does not read unnamed module` |
| [PluginClassLoaders](../src/jche/config/PluginClassLoaders.java) が拡張のコンパイルに渡すクラスパスに `jdk.module.path` も足す | jche のクラスが `java.class.path` から消えるため、`plugin.folders` の `.java` が `package jche.extension does not exist` でコンパイルできなくなる。回帰テストの `plugin` ケース（`config-custom.properties`）で実際に落ちた |

これらを施すと、`test/regression` の `whole` と `plugin`（同梱拡張）の出力は expected と一致した。
`-Xlint:all` では `[exports]` 警告（`jche.extension` の公開 API のシグネチャに JDT の型が出ている。
JDT が無名モジュールなので「export されていないモジュールの型」扱い）と、`jche.builtin` の
`[missing-explicit-ctor]` が新たに出る。`smoke.yml` は `-Werror` なので、そちらの対処も要る。

### Q12. JBang ではなぜ動かないか

JBang（0.141.0）の `--module` は、`//DEPS` で解決した jar を **すべて module-path（`javac -p` / `java -p`）に置く**。
classpath に置く選択肢は無い。そのため JBang 経由では「JDT は classpath の無名モジュール」という前提そのものが
成り立たず、Q4 の分割パッケージの壁にそのまま戻る（`requires` しなければ `package org.eclipse.jdt.core.dom is not visible`、
`requires` すれば分割パッケージのエラー）。

`--cp` で手で組んだクラスパスを渡す手はあるが、そのためには `//DEPS` を捨てて依存 jar を自分で解決することになり、
「`//DEPS` 1 行で Maven Central から取ってくる」利点を失う。

また、`//SOURCES ../../jche/**/*.java` のようにスクリプト自身を含む glob を書くと JBang が `StackOverflowError`
（ヒープ不足の形で出ることもある）で落ちる。パッケージ内の入口からモジュール全体を集めるには、
自分を除いた列挙が要る。

### Q13. この案の結論

技術的には「`java` 直叩き限定」で成立するが、採らない。

- 本流の JBang で動かない以上、「JBang ではモジュール無し、Pleiades / CI ではモジュール有り」の二重構成になり、
  同じソースが経路によって違う形で読まれる。起動経路が増えるほど検証の手間が増える
- 得られるものは Q7 の表のとおり小さく、そのために入口ファイルの移動（利用者向け手順の変更）、
  拡張コンパイルの経路変更、`--add-reads` の常用（本来は移行期の応急処置向けのフラグ）を抱える
- JBang が「`//DEPS` を classpath に置く」選択肢を持つか、JDT の分割パッケージが解消されれば（Q9）、
  Q11 の表がそのまま移行手順になる

### Q14. モジュール化すると拡張（plugin.folders）はどうなるか。`exports` で拡張の契約を明示できるのではないか

期待は正しい。`exports jche.extension;` と書けば、`URLClassLoader` で読む拡張は「そのローダの無名モジュール」になり、
export されたパッケージしか読めないので、拡張が `jche.graph` 等を import した時点でコンパイルが通らなくなる。
JDT は classpath の無名モジュールなので、拡張から `org.eclipse.jdt.core.dom` は従来どおり見える。
`uses` / `provides`（ServiceLoader）は拡張自身が `module-info` 付きの jar でないと意味が無く、
「`.java` を置くだけで動く」方式（[instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) の Q2）とは噛み合わない。

ただし、この効果の大半は JPMS 無しでも得られる。[PluginClassLoaders](../src/jche/config/PluginClassLoaders.java) は
拡張の `.java` をコンパイルするとき `java.class.path` をそのまま渡しているが、これを
「JDT の jar 群 ＋ `jche.extension` のクラスだけ」に絞れば、`exports` と同じ制限が拡張のコンパイル時にかかる。
同梱の `DiXmlProvider.java`（回帰テスト）と `builtin` の 2 実装が import しているのは `jche.extension`、JDT、`java.*` だけなので、
既存の拡張は壊れない。

| 観点 | JPMS `exports` | コンパイル用クラスパスを絞る |
| --- | --- | --- |
| 拡張の `.java` が内部パッケージを使えない | コンパイル時・実行時とも | コンパイル時のみ（jar で持ち込んだ拡張は縛れない） |
| JBang の本流で動く | 動かない（Q12） | 動く |
| 入口ファイルの移動、`--add-reads` の常用 | 必要 | 不要 |

jar で持ち込む拡張を実行時に縛れるのは JPMS だけだが、リフレクションで内部に触る拡張まで防ぐ必要は今のところ無い。

### Q15. 最終判断

**モジュール化は行わない**（2026-09）。JBang が `//DEPS` を module-path に置く限り本流で成立せず（Q12）、
得たかった「拡張の契約の明示」は Q14 の方法で足りる。JDT の分割パッケージが解消され、
かつ JBang 側の制約が変わったときに Q9・Q11 を起点に再検討する。

### 追加検討の再現手順

```bash
S=$(mktemp -d)
CP=$(bash jbangw/jbang info classpath src/CallHierarchyExporter.java | tail -1 | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
mkdir -p "$S/src/jche/jche/app"; cp -r src/jche "$S/src/jche/"
# 入口をパッケージに入れる（1 行目の import の前に package 行を足す）
sed '0,/^import/s//package jche.app;\nimport /' src/CallHierarchyExporter.java > "$S/src/jche/jche/app/CallHierarchyExporter.java"
cat > "$S/src/jche/module-info.java" <<'M'
module jche { requires java.compiler; requires java.xml; exports jche.extension; exports jche.builtin; }
M
javac --release 21 --add-reads jche=ALL-UNNAMED --class-path "$CP" --module-source-path "$S/src" -d "$S/out" -m jche -encoding UTF-8
cd test/regression/whole && rm -rf output
java --add-reads jche=ALL-UNNAMED --module-path "$S/out" --class-path "$CP" -m jche/jche.app.CallHierarchyExporter config.properties
diff --strip-trailing-cr expected/call-hierarchy.csv output/*/call-hierarchy.csv && echo same
```

---

## 検証に使った手順（再現用）

JDK 21、JBang 0.141.0、JDT 3.46.0。作業フォルダは任意。

```bash
S=$(mktemp -d)
CP=$(bash jbangw/jbang info classpath src/CallHierarchyExporter.java | tail -1 | tr ':' '\n' | grep -v '/cache/jars/')
mkdir -p "$S/mp"; for j in $CP; do cp "$j" "$S/mp/"; done
for j in "$S"/mp/*.jar; do echo -n "$(basename "$j"): "; jar --describe-module --file "$j" | head -1; done

mkdir -p "$S/src/jche/jche"
cat > "$S/src/jche/module-info.java" <<'M'
module jche { requires org.eclipse.jdt.core; requires java.compiler; }
M
cat > "$S/src/jche/jche/Probe.java" <<'P'
package jche;
public class Probe { public static void main(String[] a) {
  System.out.println(org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest()) != null); } }
P
# 全依存を module-path に → 分割パッケージのエラー
javac --module-path "$S/mp" --module-source-path "$S/src" -d "$S/out" -m jche
# jdt.core だけ module-path、残りは classpath → コンパイルは通るが実行で NoClassDefFoundError
mkdir -p "$S/mpB"; cp "$S/mp/org.eclipse.jdt.core-3.46.0.jar" "$S/mpB/"
CPB=$(echo "$CP" | grep -v org.eclipse.jdt.core-3.46.0.jar | paste -sd:)
javac --module-path "$S/mpB" --class-path "$CPB" --module-source-path "$S/src" -d "$S/outB" -m jche
java --module-path "$S/mpB:$S/outB" --class-path "$CPB" -m jche/jche.Probe
```
