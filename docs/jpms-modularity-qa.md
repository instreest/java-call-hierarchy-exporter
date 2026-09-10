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
