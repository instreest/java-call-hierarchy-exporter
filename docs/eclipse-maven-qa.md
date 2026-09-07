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
Q3 が Maven と Gradle の比較、Q5・Q6 が「`pom.xml` を書かずに済ませる方法」との比較である。

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

Maven にした。決め手は記述量ではなく、**Eclipse 側に何を追加で用意させるか**である。

Pleiades には m2e（Maven 連携）と Buildship（Gradle 連携）の両方が同梱されているので、
「プラグインがあるか」では差が付かない。差が出るのはビルドツール本体の扱いで、

- **m2e は Maven ランタイムを内蔵している**（`設定 > Maven > インストール` の Embedded）。
  したがって `pom.xml` を 1 つ置けば、Eclipse は追加のダウンロードなしに依存を解決できる。
- **Buildship は Gradle ディストリビューションを内蔵しない**。インポート時に「Gradle ラッパー」か
  「特定バージョンの Gradle」を選ばせ、前者ならリポジトリにラッパーが要り、後者なら Buildship が
  Gradle 本体（100MB 超）をその場でダウンロードする。

Gradle にした場合にリポジトリへ増えるのは `build.gradle` 1 ファイルでは済まない。ラッパーを選ぶなら
`gradlew`（約 8.7KB）・`gradlew.bat`（約 2.9KB）・`gradle/wrapper/gradle-wrapper.properties`・
そして **`gradle/wrapper/gradle-wrapper.jar`（約 43KB のバイナリ）** の 4 ファイルをコミットすることになる。
このリポジトリは jbang 本体の jar すらコミットしない方針（`.gitignore` の `/jbangw/.jbang/`）なので、
ビルド時に実行されるバイナリを版管理に入れるのは方針と合わない。ラッパーを置かない場合は、
利用者が初回インポートで Gradle 本体を取得することになり、Issue が想定する閉域環境では詰む。

もう 1 つの差は **実行 JVM の版の上限**である。Gradle は「Gradle 自身を動かす JVM」の版に上限があり、
現行ドキュメントでは JVM 17〜27、Java 25 への対応は Gradle 9.1.0 からである。対応外の JDK では
Gradle は起動を拒否する。Buildship が使うのは Eclipse 自身を動かしている JVM なので、
Pleiades 同梱の JDK が使っている Gradle の上限を超えると、**インポートそのものが失敗する**。
Maven にはこの形の上限が無く、`pom.xml` の `release` は解析対象の言語レベルを言っているだけなので
（Q1）、Eclipse が新しい JDK で動いていても壊れない。利用者の Eclipse の版を選べない配布物としては、
この差は小さくない。

記述量はむしろ Gradle のほうが短い。同等の設定を実際に書いて `gradle compileJava` が通ることを
確認したものが次の 18 行で（`settings.gradle` は無くてもプロジェクト名がフォルダ名になるだけなので不要）、
コメントを除いた `pom.xml` の約 30 行より短い。将来乗り換えるならこれが出発点になる。

```groovy
plugins {
    id 'java'
    id 'eclipse'
}
repositories { mavenCentral() }
dependencies {
    implementation 'org.eclipse.jdt:org.eclipse.jdt.core:3.46.0'
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
sourceSets {
    main {
        java { srcDirs = ['src'] }
    }
}
compileJava.options.encoding = 'UTF-8'
```

整理すると次のとおり。

| 観点 | Maven（m2e）＝採用 | Gradle（Buildship） |
|---|---|---|
| リポジトリに増えるファイル | `pom.xml` の 1 つ | `build.gradle` ＋ ラッパー 4 ファイル（うち 1 つはバイナリ jar）。ラッパー無しなら 1 つ |
| Eclipse 側の追加ダウンロード | 不要（m2e が Maven を内蔵） | ラッパー無しの場合は Gradle 本体 100MB 超 |
| 閉域ネットワーク | 依存は取れない（Q16）。ツール本体は要らない | 依存に加えて Gradle 本体も取れない。条件が 1 つ増える |
| 実行 JVM の版 | 上限なし | Gradle の版ごとに上限あり。Eclipse の JVM が新しすぎるとインポート失敗 |
| 記述量 | 約 30 行（コメント除く） | 18 行。Gradle が短い |
| 版の食い違い検査 | XML なので `awk` で座標を取り出せる（`test/pom/run.sh`） | Groovy DSL を正規表現で拾うことになる。大差はない |
| 日本の Eclipse 現場での通り | Maven のほうが通りがよい（副次的な理由） | — |

なお、この比較は「Eclipse で開くためのファイル」としての比較である。ビルドの本流は引き続き JBang で、
`pom.xml` を採ったことでビルドやリリースの方式が Maven に寄るわけではない（Q11、および「限界」）。

### Q4. `jbang edit` を使えば済むのでは

`jbang edit src/CallHierarchyExporter.java` は、`//DEPS` から依存を解決した Gradle プロジェクトを
`~/.jbang/cache/projects/` 配下に生成し、ソースはそこへのリンク（リンクが張れない環境ではコピー）にする。
Eclipse で開くのはその生成先で、リポジトリのフォルダではない。コピーになった場合は
編集がリポジトリに戻らず、git 管理との相性も悪い。Issue の「Eclipse でプロジェクトを開いたとき」は
リポジトリのフォルダを直接開くことだと解釈し、`pom.xml` をリポジトリに置く形にした。

### Q5. jbang-eclipse プラグインを使えば `pom.xml` は要らないのでは

要らなくなるが、採らなかった。

[jbang-eclipse](https://github.com/jbangdev/jbang-eclipse) は JBang 本家（jbangdev）が出している
Eclipse 連携プラグインで、`//DEPS`・`//JAVA`・`//SOURCES` をそのまま解釈する。スクリプトを右クリックして
「Synchronize JBang」を実行すると、`JBang Dependencies` というクラスパスコンテナがビルドパスに載る。
依存の定義が `//DEPS` 行 1 か所で済むので、Q7 の重複も起きない。理屈のうえではいちばん筋がよい。

それでも採らなかった理由は 3 つある。

1. **作者自身が POC と位置づけている。** README に "This experimental plugin"、
   Marketplace の説明にも "very Alpha-quality project. Moderate your expectations" とある。
2. **m2e と共存できない。** README の Caveats に、m2e / Buildship がクラスパスの変更と衝突し、
   「Maven プロジェクトの構成を更新すると JBang のソースフォルダとクラスパスコンテナが消えるので、
   Synchronize JBang をやり直す必要がある」と明記されている。つまりこの `pom.xml` と同居させると、
   どちらか一方が壊れ続ける。**両方を入れてはいけない。**
3. **導入の前提が重い。** Marketplace からのプラグイン導入（＝ネットワーク）に加えて、
   jbang の実行ファイルが PATH か `~/.jbang/bin` に必要になる。Issue が想定する閉域の Pleiades とは相性が悪い。

裏を返すと、ネットワークが使えて Eclipse にプラグインを足せる環境なら、`pom.xml` を消して
このプラグインに寄せる選択はあり得る。その場合は `test/pom/run.sh` と smoke.yml の `pom` ジョブも一緒に消すこと。

### Q6. `jbang export maven` で `pom.xml` を生成すれば、手書きしなくてよいのでは

生成物をそのまま使うことはできない。実際に実行して確かめた。

```
$ jbang export maven -O out src/CallHierarchyExporter.java
[jbang] Exported as maven project to .../out
out/pom.xml
out/src/main/java/CallHierarchyExporter.java
out/src/main/java/jche/...
```

依存は `org.eclipse.jdt:org.eclipse.jdt.core:3.46.0` の 1 件で、手書きした `pom.xml` と一致していた。
つまり「`//DEPS` から `pom.xml` の依存を起こす」ところまでは正しく動く。使えないのは形のほうで、

- **ソースを出力先へコピーして Maven 標準レイアウト（`src/main/java`）に並べ替える。**
  リポジトリのフォルダをそのまま Eclipse で開く、という目的に対して、これは `jbang edit` と同じ問題（Q4）。
- **`<release>` に `//JAVA` の値をそのまま入れる。** この構成なら 25 になり、言語レベルを 17 とした
  判断（Q1・Q2）と食い違う。
- `groupId` は `org.example.project`、`version` は `999-SNAPSHOT` の既定値になる（Q9 で決めた値と違う）。

生成した `pom.xml` を CI で作らせて、リポジトリの `pom.xml` と依存だけ突き合わせる、という使い方は考えられる。
採らなかったのは、それをやるには CI に jbang とネットワーク（JDK 25 の取得を含む）が要るのに対し、
`test/pom/run.sh` はファイルを 2 つ読むだけで同じ食い違いを検出できるから。依存が 1 件しかない今の規模では
割に合わない。依存が増えて手で揃えるのが辛くなったら、この方式に切り替える余地はある。

### Q7. 依存を `//DEPS` 行と `pom.xml` の 2 か所に書く重複をどう扱うか

重複は受け入れて、食い違いを検査で捕まえることにした。

JBang に `pom.xml` を読ませる、あるいは `//DEPS` から `pom.xml` を生成する、という一元化を考えたが、

- JBang は `//DEPS` 行しか見ない（`pom.xml` を依存の定義として読む機能は無い）
- 生成にすると「生成物をコミットするか」「生成をいつ走らせるか」という別の問題が増える

ので、2 か所に書いて `test/pom/run.sh` で `group:artifact:version` の集合が一致することを見る。
JDT の版を上げるときは両方を書き換える。片方だけ変えると GitHub Actions が落ちる。
依存が 1 件しか無いので、この程度の仕組みで十分と判断した。

### Q8. ソースフォルダを Maven 標準の `src/main/java` に移さないのはなぜか

JBang の `//SOURCES jche/**/*.java`、README の `javac -sourcepath src`、CI の `find src`、
`.gitignore` の記述がすべて `src` 直下を前提にしている。Eclipse のためだけにそれらを全部変えるのは
本末転倒なので、`pom.xml` 側で `<sourceDirectory>src</sourceDirectory>` と指定して合わせた。

`samples/demo/` の Java ソースは解析対象のサンプルであって、ツールの一部ではない。
`src` の外にあるので Maven からもビルドパスからも自然に外れる。
`test/` にも Java ソースは無い（シェルスクリプトと期待出力の CSV だけ）ので、
`testSourceDirectory` は既定（存在しない `src/test/java`）のままでよい。

### Q9. `groupId` / `artifactId` / `version` は何にするか

Maven Central に公開する予定は無いので、識別子としての意味しか無い。
`artifactId` は m2e が Eclipse のプロジェクト名にするので、リポジトリ名と同じ `java-call-hierarchy-exporter` にした。
`groupId` は GitHub の慣例に従って `io.github.instreest`。`version` はリリース管理を Maven でしないので
`0-SNAPSHOT` に固定し、上げない。

### Q10. `project.build.sourceEncoding` は必要か

必要。ソースには日本語コメントが UTF-8 で書かれている。m2e はこの値を Eclipse プロジェクトの
テキストファイルエンコードにも反映するので、これが無いと日本語 Windows では MS932 で開いてしまい、
コメントが化けるうえに Maven のビルドでも警告が出る。

### Q11. `-Xlint:all -Werror` や doclint を `pom.xml` にも入れるか

入れない。それは CI（`smoke.yml` の javac 直接実行）の役目で、`pom.xml` は「Eclipse で開けること」だけに
絞る。Eclipse の警告設定は javac の `-Xlint` とは体系が違い、Maven に `-Werror` を入れても
Eclipse のエディタには反映されない。逆に Maven のビルドだけが警告で落ちる状況を作ると、
Eclipse 上では問題無いのに `mvn compile` が通らないという混乱のもとになる。

### Q12. maven-compiler-plugin の版を固定するか

固定しない。Maven 3.9 系（m2e が内蔵する版も同じ）が既定で使う版（3.11 以降）は `release` に対応している。
plugin を明示すると版の更新という保守項目が 1 つ増える。`pom.xml` の目的からして、
Maven の既定で足りる範囲に留めたい。もし将来 Maven の既定 plugin が `release` を解釈しなくなる
ようなことがあれば、GitHub Actions の `mvn compile` で気づける。

---

## 実装

### Q13. m2e が生成する `.project` `.classpath` `.settings/` はコミットするか

しない。既に `.gitignore` に入っている。これらは m2e が `pom.xml` から毎回生成するもので、
コミットすると利用者ごとの Eclipse の版や JDK の登録名がリポジトリに混ざる。
Maven のビルド出力先 `target/` は入っていなかったので、`.gitignore` に足した。

### Q14. GitHub Actions で `mvn compile` を回す必要はあるか

回す。`test/pom/run.sh` は「版の食い違い」しか見ないので、`pom.xml` そのものが壊れている
（XML の誤り、依存の座標の誤字、Maven Central から取れない版）ことは検出できない。
m2e が行う依存解決は Maven のそれと同じなので、`mvn compile` が通れば Eclipse でも解決できると
見なしてよい。JDK は `release` と同じ 17 にした。25 で回してもよいが、「17 で足りる」ことを
CI が保証しているほうが、Pleiades 同梱の JDK が 17 の利用者にとって意味がある。

`regression` ジョブとは別ジョブにしたのは、`~/.m2` のキャッシュキーを jbang 用のもの
（`jbangw/jbang` と `src/CallHierarchyExporter.java` のハッシュ）と混ぜないため。
`setup-java` の `cache: maven` は `pom.xml` のハッシュをキーにするので、独立させたほうが素直。

### Q15. Eclipse からの実行手順で注意することは

「Java アプリケーション」として `CallHierarchyExporter` を実行し、実行構成の引数に
`config/config.properties` を渡す。作業ディレクトリは既定でプロジェクト直下なので、
README の jbang の例と同じ相対パスがそのまま使える。

ログの文字コードは、ツール側が `System.out` の文字コードを指定しない方針
（`src/CallHierarchyExporter.java` の冒頭コメント、[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q21）
なので、Eclipse のコンソールに合わせて Eclipse 側が JVM に渡す設定に従う。
CSV の入出力は常に明示的な文字コードなので影響しない。

### Q16. 閉域ネットワーク（Maven Central に届かない環境）ではどうするか

`pom.xml` は役に立たない。README にある「Pleiades の `plugins/` から jar を `lib/` に集める」手順が
引き続きそのための経路で、Eclipse で開きたければ集めた `lib/` の jar を手でビルドパスに足す。
`pom.xml` に `<scope>system</scope>` で `lib/` の jar を並べる案も考えたが、版がファイル名に
入っていて Eclipse の版ごとに変わるうえ、system スコープは Maven が非推奨にしているので採らなかった。
社内の Maven リポジトリ（Nexus 等）がある環境なら、利用者側の `settings.xml` でミラーを指すだけで
この `pom.xml` がそのまま使える。

---

## 検証

### Q17. 実際に Eclipse で開いて確かめたか

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
- **jbang-eclipse プラグインとの併用** … 対応しない。両方を入れるとクラスパスの取り合いになり、
  どちらか一方が壊れ続ける（Q5）。どちらか片方だけを使うこと。
- **IntelliJ IDEA** … `pom.xml` があれば IDEA でもそのまま開けるはずだが、確かめていないし対象にもしていない。
