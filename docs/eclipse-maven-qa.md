# Eclipse（Pleiades）での依存解決 — 実装時の QA 一覧

[Issue #39](https://github.com/instreest/java-call-hierarchy-exporter/issues/39)
「Eclipse（Pleiades）で依存性を解決したい」への対応で、迷ったこと・困ったことと、
その結論を Q&A の形で残す。

対応の要点:

- リポジトリ直下に `pom.xml` を置き、Eclipse 同梱の m2e（Maven 連携）に依存 jar を取得させる
- `pom.xml` の依存は `//DEPS` 行と同じ 1 件だけ。推移的な依存は Maven Central の POM から解決される
- ビルドの本流は引き続き JBang。`pom.xml` は「Eclipse で開くため」だけのもので、jbang の実行には関与しない
- `//DEPS` 行と `pom.xml` の版の食い違いを `test/pom/run.sh` で検出し、GitHub Actions では
  `mvn compile` で実際に解決・コンパイルできることも確かめる

Q1 は Issue の Requirements（「コンパイル Java バージョンは不要」）と結論が異なるので、最初に読んでほしい。

---

## 設計

### Q1. Issue では「コンパイル Java バージョンは不要」とあるのに、`maven.compiler.release` を書いたのはなぜか

書かないと Eclipse 上でエラーだらけになるため。

m2e は Maven の compiler 設定から Eclipse の「コンパイラー準拠レベル」を決める。何も書かないと
maven-compiler-plugin の既定値である **1.8** が採用され、プロジェクトは Java 8 の文法で構成される。
このツールのソースは `record`、`instanceof` のパターン、`switch` のアロー構文を使っているので、
Java 8 の文法ではほぼ全ファイルがコンパイルエラーになる。依存 jar が解決できても、
これでは Eclipse で開けたことにならない。

Issue の「不要」は「Eclipse には JDK を自動取得する仕組みが無いから、JDK の版を指定しても
意味が無い」という趣旨と読んだ。それは正しく、`pom.xml` に書いた `release` は **JDK の版ではなく
言語レベル**（ソースが使う文法の最低版）で、JDK の取得には関係しない。
値は `.github/workflows/smoke.yml` の `javac --release 17` と同じ 17 にした。
Eclipse はこの値に合う JDK をワークスペースに登録済みのものから選ぶだけで、無ければ
「JavaSE-17 と厳密に互換な JRE が無い」という警告を出しつつ、登録済みの新しい JDK
（Pleiades 同梱の 21 など）で代用する。エラーにはならない。

言語レベルすら書かないという選択肢が無いか探したが、m2e には「ワークスペース既定の JDK に合わせる」
という設定が無く、書かなければ 1.8 に固定される。したがって書くしかないと判断した。
Issue の意図と違っていれば、この 1 行を消すだけで Requirements どおりの形になる（ただし上記のとおり動かない）。

### Q2. 実行 JDK の版（`//JAVA 25`）を `pom.xml` に書かないのはなぜか

Eclipse に「JDK 25 を取ってきて使え」と指示する仕組みが無いから（Issue の Requirements のとおり）。
Maven のツールチェーン機能（`toolchains.xml`）は「既にインストールされている JDK の中から選ぶ」
もので、無ければエラーになるだけ。利用者の `~/.m2/toolchains.xml` に依存する設定を
リポジトリに持ち込んでも、開けない環境が増えるだけなのでやめた。

結果として、Eclipse から `CallHierarchyExporter` を実行したときは、ワークスペースの JDK
（Pleiades なら同梱の 17 か 21）で動く。JDT は実行中の JVM の標準クラスを解析対象の
クラスパスに含めるため、jbang 経由（JDK 25）と解析結果が一部異なりうる。この差は既知で、
[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q20 に具体例がある。
また実行 JDK が変わるとキャッシュは全件作り直しになるので（同 Q7）、jbang と Eclipse で
同じ `config.properties` を交互に使うと毎回全件解析になる。README にこの注意を書いた。

### Q3. Maven（m2e）と Gradle（Buildship）のどちらにするか

Maven にした。理由は「リポジトリに置くものが XML 1 ファイルで済み、Eclipse 側に追加のダウンロードが要らない」から。

Pleiades には m2e と Buildship の両方が同梱されている。しかし Buildship で開くには Gradle 本体が必要で、
利用者の環境に無ければ Gradle Wrapper（`gradlew` とバイナリの `gradle-wrapper.jar`）をリポジトリに
入れて Gradle 本体（100MB 超）を取得させるか、Buildship 同梱の Gradle を使う設定を利用者に
させることになる。m2e は Maven 本体を内蔵しているので、`pom.xml` だけあれば依存の取得まで完結する。
Java の企業案件で Eclipse を使う現場では Maven のほうが通りがよい、というのも副次的な理由。

JBang 自身の `jbang edit` で生成される Gradle プロジェクトは、
一時ディレクトリにソースへのリンクを張った別プロジェクトで、このリポジトリのフォルダを
そのまま Eclipse で開く用途には合わない（Q4）。

### Q4. `jbang edit` を使えば済むのでは

`jbang edit src/CallHierarchyExporter.java` は、`//DEPS` から依存を解決した Gradle プロジェクトを
`~/.jbang/cache/projects/` 配下に生成し、ソースはそこへのリンク（リンクが張れない環境ではコピー）にする。
Eclipse で開くのはその生成先で、リポジトリのフォルダではない。コピーになった場合は
編集がリポジトリに戻らず、git 管理との相性も悪い。Issue の「Eclipse でプロジェクトを開いたとき」は
リポジトリのフォルダを直接開くことだと解釈し、`pom.xml` をリポジトリに置く形にした。

### Q5. 依存を `//DEPS` 行と `pom.xml` の 2 か所に書く重複をどう扱うか

重複は受け入れて、食い違いを検査で捕まえることにした。

JBang に `pom.xml` を読ませる、あるいは `//DEPS` から `pom.xml` を生成する、という一元化を考えたが、

- JBang は `//DEPS` 行しか見ない（`pom.xml` を依存の定義として読む機能は無い）
- 生成にすると「生成物をコミットするか」「生成をいつ走らせるか」という別の問題が増える

ので、2 か所に書いて `test/pom/run.sh` で `group:artifact:version` の集合が一致することを見る。
JDT の版を上げるときは両方を書き換える。片方だけ変えると GitHub Actions が落ちる。
依存が 1 件しか無いので、この程度の仕組みで十分と判断した。

### Q6. ソースフォルダを Maven 標準の `src/main/java` に移さないのはなぜか

JBang の `//SOURCES jche/**/*.java`、README の `javac -sourcepath src`、CI の `find src`、
`.gitignore` の記述がすべて `src` 直下を前提にしている。Eclipse のためだけにそれらを全部変えるのは
本末転倒なので、`pom.xml` 側で `<sourceDirectory>src</sourceDirectory>` と指定して合わせた。

`samples/demo/` の Java ソースは解析対象のサンプルであって、ツールの一部ではない。
`src` の外にあるので Maven からもビルドパスからも自然に外れる。
`test/` にも Java ソースは無い（シェルスクリプトと期待出力の CSV だけ）ので、
`testSourceDirectory` は既定（存在しない `src/test/java`）のままでよい。

### Q7. `groupId` / `artifactId` / `version` は何にするか

Maven Central に公開する予定は無いので、識別子としての意味しか無い。
`artifactId` は m2e が Eclipse のプロジェクト名にするので、リポジトリ名と同じ `java-call-hierarchy-exporter` にした。
`groupId` は GitHub の慣例に従って `io.github.instreest`。`version` はリリース管理を Maven でしないので
`0-SNAPSHOT` に固定し、上げない。

### Q8. `project.build.sourceEncoding` は必要か

必要。ソースには日本語コメントが UTF-8 で書かれている。m2e はこの値を Eclipse プロジェクトの
テキストファイルエンコードにも反映するので、これが無いと日本語 Windows では MS932 で開いてしまい、
コメントが化けるうえに Maven のビルドでも警告が出る。

### Q9. `-Xlint:all -Werror` や doclint を `pom.xml` にも入れるか

入れない。それは CI（`smoke.yml` の javac 直接実行）の役目で、`pom.xml` は「Eclipse で開けること」だけに
絞る。Eclipse の警告設定は javac の `-Xlint` とは体系が違い、Maven に `-Werror` を入れても
Eclipse のエディタには反映されない。逆に Maven のビルドだけが警告で落ちる状況を作ると、
Eclipse 上では問題無いのに `mvn compile` が通らないという混乱のもとになる。

### Q10. maven-compiler-plugin の版を固定するか

固定しない。Maven 3.9 系（m2e が内蔵する版も同じ）が既定で使う版（3.11 以降）は `release` に対応している。
plugin を明示すると版の更新という保守項目が 1 つ増える。`pom.xml` の目的からして、
Maven の既定で足りる範囲に留めたい。もし将来 Maven の既定 plugin が `release` を解釈しなくなる
ようなことがあれば、GitHub Actions の `mvn compile` で気づける。

---

## 実装

### Q11. m2e が生成する `.project` `.classpath` `.settings/` はコミットするか

しない。既に `.gitignore` に入っている。これらは m2e が `pom.xml` から毎回生成するもので、
コミットすると利用者ごとの Eclipse の版や JDK の登録名がリポジトリに混ざる。
Maven のビルド出力先 `target/` は入っていなかったので、`.gitignore` に足した。

### Q12. GitHub Actions で `mvn compile` を回す必要はあるか

回す。`test/pom/run.sh` は「版の食い違い」しか見ないので、`pom.xml` そのものが壊れている
（XML の誤り、依存の座標の誤字、Maven Central から取れない版）ことは検出できない。
m2e が行う依存解決は Maven のそれと同じなので、`mvn compile` が通れば Eclipse でも解決できると
見なしてよい。JDK は `release` と同じ 17 にした。25 で回してもよいが、「17 で足りる」ことを
CI が保証しているほうが、Pleiades 同梱の JDK が 17 の利用者にとって意味がある。

`regression` ジョブとは別ジョブにしたのは、`~/.m2` のキャッシュキーを jbang 用のもの
（`jbangw/jbang` と `src/CallHierarchyExporter.java` のハッシュ）と混ぜないため。
`setup-java` の `cache: maven` は `pom.xml` のハッシュをキーにするので、独立させたほうが素直。

### Q13. Eclipse からの実行手順で注意することは

「Java アプリケーション」として `CallHierarchyExporter` を実行し、実行構成の引数に
`config/config.properties` を渡す。作業ディレクトリは既定でプロジェクト直下なので、
README の jbang の例と同じ相対パスがそのまま使える。

ログの文字コードは、ツール側が `System.out` の文字コードを指定しない方針
（`src/CallHierarchyExporter.java` の冒頭コメント、[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q21）
なので、Eclipse のコンソールに合わせて Eclipse 側が JVM に渡す設定に従う。
CSV の入出力は常に明示的な文字コードなので影響しない。

### Q14. 閉域ネットワーク（Maven Central に届かない環境）ではどうするか

`pom.xml` は役に立たない。README にある「Pleiades の `plugins/` から jar を `lib/` に集める」手順が
引き続きそのための経路で、Eclipse で開きたければ集めた `lib/` の jar を手でビルドパスに足す。
`pom.xml` に `<scope>system</scope>` で `lib/` の jar を並べる案も考えたが、版がファイル名に
入っていて Eclipse の版ごとに変わるうえ、system スコープは Maven が非推奨にしているので採らなかった。
社内の Maven リポジトリ（Nexus 等）がある環境なら、利用者側の `settings.xml` でミラーを指すだけで
この `pom.xml` がそのまま使える。

---

## 検証

### Q15. 実際に Eclipse で開いて確かめたか

確かめていない。この対応を行った環境には Eclipse が無く、Maven（`mvn compile`）で
「依存が解決でき、`src` 配下のソースが `release 17` でコンパイルできる」ことまでを確認した。
m2e の依存解決は Maven と同じ仕組みなので、Eclipse でも解決できるはずだが、
「インポート > 既存の Maven プロジェクト」の手順そのもの、コンパイラー準拠レベルの反映、
JDK の選択（Q1 の警告の出方）は、Pleiades で実際に開いて確かめてほしい。

---

## 限界（対応しないと決めたこと）

- **JDK の自動取得** … Eclipse にはその仕組みが無い（Issue の Requirements のとおり）。
  jbang 経由なら `//JAVA 25` で自動取得される。
- **Eclipse からの実行結果と jbang からの実行結果の完全一致** … 実行 JDK が違うので保証しない（Q2）。
  正式な出力は jbang 経由で作る、という運用のまま。
- **`pom.xml` でのリリース・配布** … `mvn package` で jar は作れるが、配布形態としては整えていない
  （依存 jar を同梱しないので単体では動かない）。配布は従来どおり jbang か README の手動コンパイル手順。
- **IntelliJ IDEA** … `pom.xml` があれば IDEA でもそのまま開けるはずだが、確かめていないし対象にもしていない。
