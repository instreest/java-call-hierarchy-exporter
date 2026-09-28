# 解決・キャッシュ・選択の 3 層と、規定との対応表

このツールが「呼び出し先の本体」をどう決めているかを、Java 言語仕様（JLS）と JVM 仕様（JVMS）の
**解決（resolution）** と **選択（selection）** の 2 段に対応づけて説明し、規則ごとに
「どのコードが・どの事実から・どう決め・どの検査が見ているか」を 1 つの表にまとめる。
機能要件を変えるとき、影響がどの層に閉じるかをこの表で判断する。健全性に疑いがある箇所は最後の「点検表」に集めてある
（見つかった問題は別途直す。ここでは位置と疑いを明らかにするだけ）。

書き手（`jche.analysis`）・キャッシュ（`jche.cache`）・読み手（`jche.graph`）の各パッケージの `package-info.java` は、
この文書の要約と「読む順」を持つ。コードから入るときはそちらから。

## 1. 構図

JVM は呼び出し命令を 2 段で処理する（JVMS 5.4.3.3 / 5.4.3.4 と 5.4.6）。

| 段 | JVMS | 何をするか | 入力 | 出力 | 一度決まれば同じか |
|---|---|---|---|---|---|
| 解決 | 5.4.3.3（メソッド）/ 5.4.3.4（インターフェースメソッド） | javac が書いたシンボリック参照（受け手の静的型 + 名前 + ディスクリプタ）を、実在する宣言 mR に結び付ける | クラスファイルの参照 | 宣言 mR | 同じ（5.4.3。解決は繰り返しても同じ結果） |
| 選択 | 5.4.6 | 受け手の実行時のクラス C と mR から、動く本体を決める | C・mR | 本体 | 呼び出し 1 回ごと（C が違えば違う） |

ソースを読むこのツールでは、次のように 3 層に対応させている。

```
  解決（jche.analysis。フェーズ1・書き手）
     呼び出し式 → コンパイル時宣言（JLS 15.12.1〜15.12.3）。JDT の IMethodBinding に任せ、結果だけを事実にする。
     JVMS のシンボリック参照に当たるのは「呼び出し先の宣言した型のキー + 修飾する型（JLS 13.1）」。
        ↓ 事実として書く
  キャッシュ（jche.cache。analysis-cache.tsv）
     解決の結果は「一度決まれば同じ」なので置いてよい。選択の結果（どの本体が動くか）は置かない。
     選択の材料になる行: C（呼び出し先・修飾子・修飾する型）、D（修飾子・本体の有無）、
     H（親型・親クラスの連鎖・継承した実装）、O（型引数を具体化した上書き）、M（ラムダが実装する鍵）。
        ↓ 読む
  選択（jche.graph。フェーズ2・読み手）
     ソースでは受け手の実行時のクラス C が 1 つに決まらないので、2 つの問いに分ける。
       (1) C としてありうる型はどれか … CallResolver の段（CHA・LOCAL_NEW・値の追跡・契約表・DI）。
           これは JVMS には無い、静的解析ならではの近似（多すぎる側に倒す）。
       (2) その C で動く本体はどれか … MethodSelection（JVMS 5.4.6 の手順。実装探索の入口はここだけ）。
     静的束縛（invokestatic / invokespecial に当たる呼び出し）は BindKind が先に決め、(1)(2) を通さない。
```

この整理の出発点になった「解決結果がキャッシュされ、それを入力として選択が毎回行われる」という構図と同じで、
JDT の `IMethodBinding` が解決、`MethodSelection` が選択に当たる。`CallResolver` の段は、実行時なら受け手のオブジェクトを
見れば済む「C は何か」を静的に近似する部分で、JVMS の外にある。健全性を疑うときは、どの層の話かをまず分ける。

## 2. 命令ごとの対応

| JVMS の命令 | ソースの形 | 選択の扱い（JVMS） | このツール |
|---|---|---|---|
| `invokestatic` | `T.m()`・static import | 選択なし。解決結果がそのまま呼ばれる | C 行の calleeMods に `static` → `BindKind.STATIC` → 段 0 で `STATIC_BOUND:STATIC`（宣言のまま確定） |
| `invokespecial` | `new T()`・`this(...)`・`super(...)`・暗黙の `super()`・`super.m()`・`I.super.m()`・private の呼び出し（Java 11 以降は nestmate で `invokevirtual` にもなるが、選択は無い） | 受け手の実行時のクラスを使わない | コンストラクタ → `BindKind.CONSTRUCTOR`、`super` の印（`ModifierTokens.SUPER`）→ `SUPER`、`private` → `PRIVATE`。どれも段 0 で確定。暗黙の `super()` の呼び出し先は書き手が候補すべてに辺を張る（JLS 15.12.2.5 の最も特殊なものを自前で選ばない。`docs/jls-conformance-qa.md` の Q35） |
| `invokevirtual` | クラス型の受け手の `o.m()`・`m()` | 5.4.6 の選択 | `BindKind.VIRTUAL`（final のメソッド・final のクラスは `FINAL_METHOD` / `FINAL_CLASS` で静的束縛。JVM も上書きされない）→ 段 1〜6 で C の候補 → 候補ごとに `MethodSelection.implementationOf` |
| `invokeinterface` | インターフェース型の受け手の `o.m()` | 5.4.6 の選択（解決は 5.4.3.4） | 同上。宣言した型がインターフェースでも扱いは同じ。CHA の起点は修飾する型（JLS 13.1。C 行の qualifier） |
| `invokedynamic` | ラムダ・メソッド参照 | ブートストラップメソッドが呼び出し先を決める。実体は実装メソッドのハンドル | ラムダは合成メソッド `lambda$…`（D 行。`private lambda`）。関数型インターフェースのメソッドの呼び出しは、M 行（`hasFunctionalImpl`）と値の追跡（`Z` のノード）で本体へ繋ぐ（`CallResolver#functionalResolution`）。メソッド参照の参照先が仮想メソッドなら、束縛したレシーバの型で改めて選択する（JLS 15.13.3） |

`BindKind` は C 行の calleeMods（コンパイル時宣言の修飾子。`CallSiteRecorder#targetModsOf`）だけから決める。
「宣言型が具象クラスである」ことは静的束縛の根拠にしない（`Base b = new Derived(); b.m()`）。

## 3. 解決の側（書き手）: 何を JDT に任せ、何を事実として書くか

| 規則 | 何を決めるか | コード（書き手） | 書く行・列 | 検査 |
|---|---|---|---|---|
| JLS 15.12.1〜15.12.3 コンパイル時宣言 | 呼び出し先の宣言 | `BindingNames#toRef`（`IMethodBinding.getMethodDeclaration()` の宣言した型を消去してキーにする。実体化された型ではなく宣言） | C 行の呼び出し先（S 行の番号） | `test/jls`（javac 26 の invoke 命令の呼び出し先と C 行を両向きに突き合わせ） |
| JLS 13.1 修飾する型 | CHA の起点 | `CallSiteRecorder#qualifierOf`（式の型の消去。単純名は最も内側の囲む型。`Object` の宣言は空） | C 行の qualifier | `test/jls`（invokevirtual / invokeinterface の owner と qualifier の一致）、`s13_01` |
| JLS 8.4.3 / 8.4.8 修飾子 | 静的束縛の材料 | `CallSiteRecorder#targetModsOf`（宣言の修飾子 + 宣言した型が final なら `finalclass`）、`superMods`（`super` の印） | C 行の calleeMods、D 行の mods | `test/pruning` の `OvrStatic` / `OvrPriv` / `OvrFinal` |
| JLS 8.4.8.1 上書き（型引数の置換でキーが食い違うもの） | 上書きの事実 | `OverrideFacts#overriddenKeysOf`（推移的な親型を型引数を具体化したまま辿り、`IMethodBinding.overrides` に尋ねる。名前と引数の数は尋ねる前の絞り込みだけ。キーが同じ上書きは書かない） | O 行 | `test/jls` の `override` 行と javac のブリッジメソッドとの突き合わせ、`test/demo` の `fx.generic` |
| JLS 8.4.8.1 継承したメソッドが親インターフェースを実装する形 | その型から見た実装の組 | `OverrideFacts#inheritedImplementationsOf`（`isSubsignature`。クラスの連鎖を近い順に。キーが同じ組は書かない） | H 行の 8 列目 | `test/jls` の `InheritedImpl`（ブリッジ）、`test/pruning` の `GiRet` |
| JLS 8.1.4 / 8.1.5 親型 | 型階層 | `TypeContextTracker#collectSupertypes`（直接の親と、jar の型を経由して到達するソースの親。`java.lang.Object` は入れない） | H 行の 3 列目 | `test/incremental`（親型の連鎖）、`test/regression` |
| JLS 8.4.8 の順（クラスの連鎖が先） | 選択の 2 段目の順 | `TypeContextTracker#recordType`（親クラスの連鎖をソースの型に当たるまで。途中の jar のクラスも並べる） | H 行の 7 列目 | `test/jls` の `ClassWins` / `Dispatch`、`test/pruning` の `Dtwr` / `Dfe` / `DRun` / `DrRet` / `JarHold` |
| JLS 9.8 関数型インターフェースの上書き同等な抽象メソッド | ラムダが実装する鍵 | `OverrideFacts#functionalKeysOf`（`isSubsignature`） | M 行 | `test/jls` の `s15_27` / `s15_13`、`docs/lambda-expansion-qa.md` |
| JLS 14.14.2 / 14.20.3 / 14.30.2 構文が呼ぶメソッド | 式の無い呼び出しの呼び出し先 | `ImplicitCalls#findNoArgMethod`（式の型のメンバーを、クラスの連鎖 → 最も特定的なインターフェースの順で、public の宣言から自前で引く。JDT に式が無いので尋ねられない） | C 行 | `test/jls` の `s14_14_02` / `s14_20_03`、`test/pruning` の `MemberTv` / `PkgMember` |
| JLS 8.8.7 / 15.9.5.1 暗黙の `super()` | コンストラクタの辺 | `TypeContextTracker#recordImplicitSuper`（候補すべてに辺。匿名クラスは型引数を置き換えた引数の型で比べる） | C 行 | `test/ctorbody`、`test/jls` の `s08_08` / `GenericSuper` |

「文字列で比べている箇所」で解決に関わるのは、`ImplicitCalls`（メンバーの探索。JDT に尋ねる式が無い）と
`TypeContextTracker` の暗黙の `super()`（引数の型の並びを消去した名前で比べる）の 2 つ。ほかの文字列比較は JDT に尋ねる前の
絞り込みか、「キーが同じなら書かなくてよい」の判断で、判定そのものではない（`OverrideFacts` のクラスコメント）。

## 4. 選択の側（読み手）: JVMS 5.4.6 の手順との対応

`MethodSelection#search` の中身を JVMS の手順に沿って並べる。材料はすべてキャッシュの行で、実行時の情報は無い。

| JVMS 5.4.6 の手順 | JVMS の条件 | このツール（`MethodSelection`） | 材料の行 | 近似・違い | 検査 |
|---|---|---|---|---|---|
| 1. mR が private なら mR そのもの | ディスパッチしない | ここには来ない。`BindKind.PRIVATE` が段 0 で確定する（`super.m()` / `X.super.m()` は `BindKind.SUPER` で、段 0 が C 行の修飾する型から `implementationOf` で選び直す。#177）。`implementationOf` を private の宣言に対して引いたときは、連鎖の先頭（その型自身）だけ private を飛ばさない | C 行 calleeMods | 同じ | `test/pruning` の `OvrPriv` / `ReflPriv` |
| 2. C とその親クラスの連鎖に、mR を上書きできる宣言（5.4.5）があればそれ | 名前とディスクリプタが同じ・mC が private でない・mA が public / protected、またはパッケージアクセスで同じ実行時パッケージ（推移も可） | `search` の前半。`TypeHierarchy#classChain`（H 行の 7 列目）を根まで順に、各段で `declarationIn`（キーの一致 → パッケージアクセスなら同じパッケージか `overridesAcrossPackage`）と O 行（`declaredAmong`）と H 行の 8 列目（`inheritedImplementationIn`）を見る。本体を持つ最初の宣言を採る。その型より上の private は飛ばす | H 7 列目・D 行 mods / hasBody・O 行・H 8 列目 | ディスクリプタの一致は「消去したキーの一致」で写す（同じ意味）。ジェネリクスの上書き（javac ならブリッジ）は O 行・H 8 列目で補う。継承される static（public・protected か同じパッケージ）は飛ばさない（JLS 8.4.8.2 でコンパイルできないので仮想呼び出しでは当たらない）。継承されない static は飛ばす（#175）。O 行・H 8 列目から見つけた上書きより下の段の、同じシグネチャの宣言による推移的な上書き（JLS 8.4.8.1）は `lowestOverriderOf` が拾う（#175）。本体の無い宣言（抽象）では止まらず親へ進む。連鎖に jar のクラスが挟まる（`passesBinaryClass`）と、その宣言は見えないまま通り過ぎる（候補は落とさないが、戻り値で絞ることはしない） | `test/jls` の `ClassWins` / `Overriding` / `PackageBase` / `Widened`、`test/pruning` の `Dtwr` / `Dfe` / `DRun` / `DrRet` / `JarHold` / `GiRet` |
| 3. 無ければ、C の最も特定的な親インターフェースのメソッドのうち、非 abstract がちょうど 1 つならそれ | 0 個なら AbstractMethodError、複数なら IncompatibleClassChangeError | `search` の後半。`TypeHierarchy#superinterfaces`（連鎖の型が実装するインターフェースを近い順に）の宣言から private・static を除き、`mostSpecific` で絞り、本体を持つものを採る | H 3 列目・D 行 | 複数残るときは、ソースに本体のある宣言を jar の宣言より先にし、その中は近い順の先頭（JVM ならエラーになる形。候補を落とさない側の近似）。抽象の再宣言も「最も特定的」の判定に入れる（子インターフェースが default を抽象で消した形で、親の default を選ばない） | `test/jls` の `MostSpecific`（§9.4.1.1）、`Interfaces`、`test/pruning` の `DtwrP`（親クラスの private を飛ばして default へ） |

`implementationOfSignature`（契約表・リフレクション）は同じ手順を、宣言した型を知らないままシグネチャで引く。
同じシグネチャに消去される別々のジェネリック型を 1 つの型が両方とも上書きしていると、どちらが選ばれるかは決まらない
（`docs/jls-conformance-qa.md` の Q23。契約表がシグネチャで名指しする以上、避けられない曖昧さ）。

### 4.1 受け手の型の候補（C を静的に近似する段。JVMS の外）

| 段 | 何から C を決めるか | ラベル（`resolved-by`） | 材料 |
|---|---|---|---|
| 段 0 | 静的束縛（上の表の命令ごとの対応） | `STATIC_BOUND:*` | C 行 calleeMods |
| ラムダ | 受け手の値がラムダ・メソッド参照 | `DATAFLOW_LAMBDA` | M 行・N 行（`Z`） |
| 段 1 | 修飾する型（JLS 13.1）の部分型すべて（jar の型なら宣言した型の部分型）。部分型ごとに `implementationOf` | `NO_OVERRIDE` / `SINGLE_IMPL` / `NO_IMPL` / `GENERATED_IMPL:*` / `CHA` | H 行・C 行 qualifier |
| 段 2 | 同じメソッドの中で `new` した型 | `LOCAL_NEW(_MULTI)` | C 行 hints |
| 段 3 | 契約表（種類 C）・拡張 | `CONTRACT` / 拡張のラベル | 設定 |
| 段 4 | 値の追跡（ファクトリの戻り値・引数・フィールド・new） | `DATAFLOW_*` | N・R・J 行 |
| 経路の上限 | 経路で渡された値の宣言の型（具象クラスの型で宣言したフィールド・引数）の部分型に、段 1 の候補を絞る（`CallResolver#narrowByBound`。具象型が決まらないときだけ。段 5 の結論が上限と矛盾すれば経路の事実を採る） | `DATAFLOW_DECLARED_TYPE`（1 つに定まったとき。複数なら `CHA` のまま候補を減らす） | V 行の宣言の型・メソッドキーの引数の型（`Slot.BOUND`） |
| 段 5 | DI の Bean 定義（注入点だけ） | `SPRING_DI(_QUALIFIER)` | H・V・D 行のアノテーション |
| 段 6 | 絞れず候補が複数のまま | `CHA` | — |

どの段も、型を 1 つ決めたら必ず `MethodSelection#implementationOf` に通す（候補の型が宣言していない継承した実装も拾う）。

## 5. キャッシュの再解析の健全性との関係

選択の材料（H 行の 7・8 列目、O 行、D 行の修飾子）はファイルごとの事実なので、差分更新で「そのファイルを解析し直すか」を
間違えると、選択の結果が全件解析と静かに食い違う。差分更新が拾う依存は `CacheFormat` の I 行の説明（(a)〜(f)）と
`docs/cache-design.md` の「差分更新」にあり、選択に効くものは次のとおり。

| 変化 | 影響する選択の材料 | 差分更新が拾う仕組み | 検査 |
|---|---|---|---|
| 親の親（祖父母）の型のメソッドを足す・消す | H 7 列目の先の宣言、O 行 | 「親型の連鎖」（変わった型の部分型をすべて変わった型にする。`StaleTypes#register` の部分型の索引） | `test/incremental`（祖父母の型の変更） |
| jar の親クラス・親インターフェースのメソッドを足す | `passesBinaryClass`・`superinterfaces` の jar の宣言 | I 行 (e)（jar の型の頭に現れる型）と L 行の jar の指紋 | `test/incremental`（jar の型の親にメソッドを足す） |
| 親型の型引数を変える（`Repo<User>` → `Repo<Order>`） | O 行・H 8 列目 | I 行 (e)（親型の型引数）と、自分の宣言の指紋（I 行の 2 列目。宣言の連鎖） | `test/incremental`（型引数にだけ現れる型の親） |
| 中間のクラスが親をやめる・持つ | H 7 列目・8 列目 | 親型の連鎖 | `test/incremental`（継承した実装が変わる） |
| 同じパッケージに型を足して import を隠す | C 行の呼び出し先そのもの | 「新しい型」（同じパッケージ・そのパッケージのオンデマンド import のブロックを解析し直す。`StaleTypes#touches`。名前は照合しない） | `test/incremental` |
| 型解決に失敗していたファイル（無い型の名前は I 行に残らない） | C 行・U 行 | 何かが変わった実行では名前を照合せず必ず解析し直す（`CacheUpdater#reanalyzeDependents`） | `test/incremental`（無かった型を足す） |
| 型階層そのものが変わる（親の付け替え・入れ子の型の追加） | H 行すべて | 安全網: 解析し直したファイルの H 行が旧キャッシュと違えば残りを全件解析（`BlockWriter#hierarchyDigestOf`・`CacheUpdater#analyzeAllIfHierarchyChanged`） | `test/incremental`（親を付け替える） |

書き手の変更で H・O・D・C 行の内容が変わりうるなら、`CacheFormat.VERSION` を上げる（上げ忘れは `test/cacheversion` が捕まえる）。

## 6. 健全性の点検表（要確認）

規定と突き合わせて、今の作りが「近似」か「未対応」か「疑いがある」箇所。**ここでは直していない**（見つけたら別途直す）。
各行の「検査」は、疑いを確かめるならどこに題材を置くかの案。多すぎる側（候補を落とさない）に倒れているものは健全性の問題ではないが、
少なすぎる側（呼び出しを落としうる）のものを上に置いた。

| # | 層 | 場所 | 規定 | 疑い・近似 | 倒れる側 | 検査の置き場 |
|---|---|---|---|---|---|---|
| 1 ([#184](https://github.com/instreest/java-call-hierarchy-exporter/issues/184)) | 選択（段 1） | `TypeContextTracker#collectSupertypes` と `TypeHierarchy#transitiveSubtypes` | JLS 15.12.4.4 / JVMS 5.4.6（C の候補） | H 行の親型は「直接の親」と「jar の型を経由して到達するソースの親」だけで、jar の親の親（`class MyList extends ArrayList` の `List` / `Collection`）は載らない。宣言した型が jar のインターフェース（`java.util.List#size()`）で、修飾する型も jar の型なら、段 1 は `transitiveSubtypes("java.util.List")` から候補を引くので、`MyList.size()` が候補に入らず、呼び出しは `NO_OVERRIDE`（jar の宣言。`[EXTERNAL] no source to follow`）になる。`usableQualifier` のコメントは修飾する型についてこの穴を認めて宣言した型に倒しているが、宣言した型の側にも同じ穴がある。使い捨てのプロジェクトで確かめた: `List<String> l = …; l.size()` は `List.size RESOLVED:NO_OVERRIDE [EXTERNAL] no source to follow` になり、`MyList.size()` は `methods.csv` で `ENTRY_CANDIDATE` / `[NOT_REACHED]`。同じ形で `Supplier` を直接 implements した型は CHA の候補に入る | **少なすぎる側**（ソースの上書きが落ちる） | `test/pruning` に `class MyList extends ArrayList<String> { size() }` を `List<String>` 型の変数で呼ぶケース（`reachable`） |
| 2 | 選択 | `TypeHierarchy`（`java.lang.Object` を載せない） | JVMS 5.4.6 | `Object` のメソッド（`toString` / `equals` / `hashCode`）の呼び出しは部分型を数えないので、ソースの上書きが候補に入らない。`docs/excluded-entry-promotion-qa.md` で「載せない」と判断済み（費用に見合わない） | 少なすぎる側（判断済み） | 既知。変えるなら版上げと全件解析 |
| 3 ([#174](https://github.com/instreest/java-call-hierarchy-exporter/issues/174)) | 選択（2 段目） | `MethodSelection#overridesAcrossPackage` | JVMS 5.4.5 / JLS 8.4.8.1 | **直した（#174）**: 以前は「親型のどこかに public / protected の中間の宣言が 1 つでもあるか」で見て、同じパッケージのインターフェースの宣言も数え（`Sub extends p.Base implements p.Worker` で `Base.work` を落とす）、表に無い jar のクラスの宣言し直しは見えなかった。今は親クラスの連鎖（H 行の 7 列目）だけを呼び出し先の型まで辿り、そのパッケージの public / protected の宣言か、そのパッケージに属す H 行の無いクラスがあれば真（`docs/jls-conformance-qa.md` の Q37） | 一致（jar のクラスは多すぎる側） | `test/jls` の `s08_04_08.a` / `.b`、`test/pruning` の `XaBase` / `XaPUse` |
| 4 ([#175](https://github.com/instreest/java-call-hierarchy-exporter/issues/175)・[#168](https://github.com/instreest/java-call-hierarchy-exporter/issues/168)) | 選択（2 段目） | `MethodSelection#search`（private を飛ばす条件・パッケージアクセス） | JLS 8.4.8 / JVMS 5.4.5 | 別のパッケージの親クラスのパッケージアクセスのメソッドを、JLS では継承されないのに実装に選ぶ（`docs/jls-conformance-qa.md` の Q36。JVMS 5.4.5 では上書きしうる形で、javac の出力は実行時に `IllegalAccessError`）。**直した（#175）**: 継承されない static（別パッケージのパッケージアクセス）は飛ばす（`inheritedBy`。同じシグネチャの default を持つクラスはコンパイルでき、動くのはその default）。O 行・H 行の 8 列目から見つけた上書きを、それより下の段の同じシグネチャの宣言がさらに上書きしていれば（JLS 8.4.8.1 の推移。JDT の `overrides` は別パッケージのパッケージアクセスに対して偽）そちらを採る（`lowestOverriderOf`。Q38） | インスタンスメソッドはどちらとも言えない（判断済み）。static と推移は一致 | `test/pruning` の `PkgMember`（`close()` / `iterator()` は `ImplicitCalls` 側で除いている）・`XaImpl5` / `XaGUse` |
| 5 ([#185](https://github.com/instreest/java-call-hierarchy-exporter/issues/185)) | 選択（3 段目） | `MethodSelection#search` の後半、`TypeHierarchy#mostSpecific` | JVMS 5.4.3.3 maximally-specific | 「最も特定的」の判定は H 行の親型（`isSubtypeOf`）で行うので、jar のインターフェースを経由した親子関係（`interface A extends lib.B`、`lib.B extends C`）は見えず、両方が残って「ソース優先・近い順の先頭」で 1 つに決める | 少なすぎる側になりうる（別の default を選ぶ） | `test/pruning` に jar の中間インターフェースを挟んだ default の題材 |
| 6 ([#186](https://github.com/instreest/java-call-hierarchy-exporter/issues/186)) | 選択（別実装） | `ExternalUsageScanner#inheritedFrom` | JVMS 5.4.3.3（jar からの被参照の解決） | `MethodSelection` とは別に、同じ順（クラスの連鎖 → 最も特定的なインターフェース）を自前で持つ。O 行・H 行の 8 列目・パッケージアクセスを見ないので、ディスクリプタ `save(java.lang.Object)` の被参照は型引数を具体化した上書きではなく宣言に結び付く。JVM のディスクリプタの一致という意味では正しいが、2 つの実装が並ぶので直すときに片方を忘れる | 出力の行の結び先が違いうる（呼び出しは落ちない） | `test/pruning` の外部 jar の題材にジェネリックな上書きを足す。作りとしては `MethodSelection#implementationOfSignature` に寄せられるかを検討 |
| 7 ([#177](https://github.com/instreest/java-call-hierarchy-exporter/issues/177)) | 選択（3 段目） | `MethodSelection#search`（jar の宣言の `hasBody`） | JVMS 5.4.6 | jar のメソッドは D 行が無いので `hasBody` を true とみなす（`MethodTable`）。jar のインターフェースの抽象メソッドが「本体を持つ最も特定的な宣言」として選ばれ、ソースの default より先に来ることは無い（ソース優先）が、ソースに無い場合は抽象の宣言を返す。**直した（#177）**: `X.super.m()` / `super.m()` は修飾する型（C 行。v45）から `superTarget` が選び直す。jar のインターフェースがソースの default を宣言し直す形は `passesBinaryClass` が親インターフェースの H 行の無い型も数える。record の暗黙のアクセサは D 行を合成し、`Enum` の final メソッドは `search` が表の宣言か「分からない」を返す（`docs/jls-conformance-qa.md` の Q39） | 多すぎる側 | `test/pruning` の `Diamond` / `JarIface` / `RecAcc` / `EnumOrd` / `EnumName` |
| 8 ([#189](https://github.com/instreest/java-call-hierarchy-exporter/issues/189)) | 解決（暗黙の呼び出し） | `ImplicitCalls#findNoArgMethod` | JLS 15.12.1 / 8.4.8 | 拡張 for の `iterator()`・try-with-resources の `close()` のメンバー探索を、JDT に尋ねず自前で行う（式が無い）。public の宣言だけから引く（`Iterable` / `AutoCloseable` の実装は public でなければならない、という理由）。クラスの連鎖 → 最も特定的なインターフェースの順は `MethodSelection` と同じだが、実装が 2 つある | 一致（ただし別実装） | `test/jls` の `s14_14_02` / `s14_20_03`、`test/pruning` の `MemberTv` / `PkgMember` |
| 9 | 解決（暗黙の `super()`） | `TypeContextTracker#recordImplicitSuper` | JLS 8.8.7 / 15.9.5.1 / 15.12.2.5 | 匿名クラスの合成コンストラクタは、型引数を置き換えた親のコンストラクタの引数の型を消去した名前の文字列で比べる。ジェネリックなコンストラクタは引数の数だけ。決めきれなければ候補すべてに辺 | 多すぎる側（判断済み） | `test/ctorbody`、`test/jls` の `GenericSuper` |
| 10 ([#189](https://github.com/instreest/java-call-hierarchy-exporter/issues/189)) | 選択（リフレクション） | `DataflowResolver#invokeTargets` / `dispatchesVirtually` | JVMS 5.4.6 | `getMethod(name)` の引数の型が分からないときは名前だけで多重定義すべてを候補にする。`dispatchesVirtually` は private・static だけを見てパッケージアクセスを見ない。`implementationOfSignature` に通すので上書きの照合はそちらと同じ | 多すぎる側 | `test/pruning` の `ReflOverload` / `ReflPriv` / `ReflRecv` |
| 11 ([#188](https://github.com/instreest/java-call-hierarchy-exporter/issues/188)) | 選択（契約表） | `FrameworkEntries`（`super` の入口） | — | 親型のメソッドをシグネチャの文字列一致で見る。O 行を見ないので、型引数を具体化した上書きは入口にならない | 少なすぎる側になりうる（入口の判定だけ。呼び出しは落ちない） | `test/contracts` に題材を足す |
| 12 ([#189](https://github.com/instreest/java-call-hierarchy-exporter/issues/189)) | 値（選択ではない） | `GuardCollector`（`equals` の判定） | JLS 15.12 | 「`Object.equals` かその上書きか」を名前 `equals`・引数 1 つ・引数の型 `java.lang.Object` で決める（`overrides` を使わない） | 打ち切りの判定にだけ効く | `test/pruning` の `equals` の題材 |
| 13 ([#187](https://github.com/instreest/java-call-hierarchy-exporter/issues/187)) | 検査の側 | `test/jls/JlsCheck#resolve` | JVMS 5.4.3.3 | インターフェースの段で幅優先の最初の一致を採り、maximally-specific を計算しない（ツール本体はしている）。突き合わせの側が甘い | 検査の見落とし | `JlsCheck` を `mostSpecific` と同じ判定にする |
| 14 | 解決 | `OverrideFacts#overriddenKeysOf`（O 行はソースの `MethodDeclaration` にだけ） | JLS 8.4.8.1 | jar のメソッド・合成したメソッド（ラムダ・暗黙のコンストラクタ・`<clinit>`）・record の暗黙のアクセサには O 行が無い。record のアクセサがインターフェースのメソッドを型引数の置換つきで実装する形（`record R(String name) implements Named<String>`。`Named<T>` の `T name()` の戻り値だけが違う）はキーが同じなので落ちないが、引数の型が置換される形は無い（アクセサは引数を持たない） | 一致（構造上の限界の確認） | — |

点検表の行は Issue に起票してある（# の横の番号。#2・#9・#14 は判断済み・構造上の限界の確認なので起票していない）。行を直したら、この表の行を消すか「一致」に書き換え、Issue を閉じ、`docs/jls-conformance-qa.md` に Q を足し、`test/jls` か `test/pruning` に題材を置く。

## 7. 変更の影響範囲の判断のしかた

| 変えたいこと | 触る層 | 触るクラス | 版上げ | 検査 |
|---|---|---|---|---|
| 「どの本体が動くか」の決め方（JVMS 5.4.6 の写し） | 選択 | `MethodSelection`（と `TypeHierarchy` の並び） | 不要 | `test/jls`・`test/pruning`・`test/regression` |
| 受け手の型の候補の絞り方・並び | 選択 | `CallResolver`（段）・`SpringBeans`・`TypeContracts` | 不要 | `test/dataflow`（順序非依存）・`test/regression`・`test/pruning` |
| 経路の環境に入れる枠（具象型・値・上限） | 選択 | `DataflowResolver#bindArgs`・`Slot`・`jche.report.StreamingTreeWalker` | 不要（宣言の型は V 行・メソッドキーに既にある） | `test/pruning`（`Dt*` / `SbDecl*`） |
| 呼び出し先・修飾子・修飾する型の読み方（JLS の写し） | 解決 | `CallSiteRecorder`・`BindingNames`・`TypeContextTracker`・`ImplicitCalls` | 必要 | `test/jls`・`test/cacheversion --update` |
| 上書き・実装の関係の事実 | 解決 | `OverrideFacts` | 必要 | `test/jls`（ブリッジとの突き合わせ）・`test/cacheversion --update` |
| 差分更新が拾う依存 | 解決（キャッシュ更新） | `StaleTypes`・`BindingNames`（I 行）・`CacheUpdater` | I 行の中身が変わるなら必要 | `test/incremental` |
| 行の形式・並び | キャッシュ | `CacheFormat`・各 record・`BlockWriter`（書き手）・`CallGraphBuilder`（読み手） | 必要 | `test/cachevalue`・`test/incremental`・`test/cacheversion --update` |
| 注記・`resolved-by` の文言 | 出力 | `jche.report` | 不要 | `test/regression`（期待値の更新） |
