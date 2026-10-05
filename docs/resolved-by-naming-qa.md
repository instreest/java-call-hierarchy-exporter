# `resolved-by` の名前を仕様に合わせる — Q&A

`resolved-by` 列（と U 行・被参照の行）の値のうち、Java 言語仕様（JLS）・JVM 仕様（JVMS）の用語と食い違って
読み違いを招いていた 3 つの名前を改めた判断を残す。
関連: [call-hierarchy-columns-qa.md](call-hierarchy-columns-qa.md)（列の語彙）、
[resolution-selection-design.md](resolution-selection-design.md)（命令ごとの対応）、[external-usage.md](external-usage.md)。

## 対応の要点

| 以前 | 今 | 種類 |
|---|---|---|
| `UNRESOLVED:OUTSIDE_METHOD` | `UNRESOLVED:CALLER_UNRESOLVED` | U 行の理由コード（キャッシュに入る。形式 v46） |
| `EXTERNAL_USAGE:IMPLICIT_CTOR`（注記 `external-ref:IMPLICIT_CTOR`） | `EXTERNAL_USAGE:MISSING_NOARG_CTOR` | 被参照の照合の種類（キャッシュに入らない） |
| `RESOLVED:STATIC_BOUND:FINAL_METHOD` / `FINAL_CLASS` | `RESOLVED:NOT_OVERRIDABLE:FINAL_METHOD` / `FINAL_CLASS` | 段 0 の解決ラベル（読み手だけ） |

どれも**何をどう解決するかは変えていない**。変わるのは出力の値の綴りと、U 行の注記の文言だけである。

## Q&A

### Q1. `OUTSIDE_METHOD` の何がおかしかったか

README は「メソッド本体の外からの呼び出し」と説明していたが、この理由コードを付けるのは
`CallSiteRecorder#record` で呼び出し元の一覧が取れないとき、つまり**囲むメソッド・コンストラクタ・型のバインディングを
JDT が解決できなかったとき**である（`TypeContextTracker#clinitCallers` / `instanceInitCallers` の `UNKNOWN_CALLER`、
`FactVisitor` のメソッドの束縛が null のとき）。フィールドの初期化子・初期化ブロックの呼び出しは、JLS 12.4.2・12.5 のとおり
`<clinit>` / `<init>` を呼び出し元にして普通の辺になるので、「メソッドの外」の呼び出しはここに来ない。
名前も説明も実態と逆向きに読めたので、`BINDING_FAILED`（呼び出し先の側の失敗）と対になる `CALLER_UNRESOLVED`
（呼び出し元の側の失敗）にした。注記の文言も `call from outside a method body` から
`caller unresolved (type resolution of the enclosing method or type failed)` にした。

**形式の版**: 理由コードは U 行に書き手が書く文字列なので、`jche-cache-v46` に上げた。古いキャッシュを読んで
`OUTSIDE_METHOD` のまま出力することは無い。

**却下**: キャッシュには `OUTSIDE_METHOD` を残し、出力のときだけ読み替える案。版を上げずに済むが、キャッシュと
出力で同じものの名前が 2 つになり、`CacheDump` で見たときに読み違える。迷ったら上げる決まりに従った。

### Q2. `IMPLICIT_CTOR` の何がおかしかったか

JLS 8.8.9 で implicit（default）constructor といえば「コンストラクタを宣言しなかったクラスにできるもの」である。
このツールはそれを解析時に D 行として合成するので、暗黙のコンストラクタへの jar からの参照は `EXACT` で照合される。
`IMPLICIT_CTOR` が付くのは逆に、**引数なしコンストラクタへの参照なのに今のソースに一致する宣言が無い**（相手の jar を
ビルドした時点では引数なしで生成できたが、今は無い＝版違いの疑い）ときだけだった。仕様の用語と逆の意味に読めるので、
起きていることそのままの `MISSING_NOARG_CTOR` にした。実行ログの件数の見出しも「暗黙コンストラクタ」から
「引数なしコンストラクタの欠け」（英語は `missing no-arg constructors`）にした。

### Q3. final のメソッド・final のクラスを `STATIC_BOUND` から分けたのはなぜか

JLS 15.12.3 の呼び出し方式は static / nonvirtual（private）/ super / interface / virtual で、final のメソッドや
final のクラスのメソッドは **virtual**（バイトコードも `invokevirtual`）である。「静的に束縛される」のではなく、
上書きできない（JLS 8.4.3.3・8.1.1.2）ので JVMS 5.4.6 の選択が呼び出し先の宣言そのものになる、というのが正しい理由である。
扱い（候補は呼び出し先の 1 件で確定・拡張の `appliesToStaticBound()` の対象）は変えず、ラベルだけを
`NOT_OVERRIDABLE:FINAL_METHOD` / `NOT_OVERRIDABLE:FINAL_CLASS` に分けた（`BindKind#label`）。
`STATIC_BOUND:*` は仕様の上でも仮想呼び出しでないもの（`PRIVATE` / `STATIC` / `SUPER` とコンストラクタの `CTOR`）だけになる。

**キャッシュ**: 段 0 のラベルは C 行の修飾子から読み手が作るので、キャッシュの事実は変わらず、形式の版は上げない。

**却下**:
- 接頭辞 `MONOMORPHIC:`。型の流れの解析の用語で、Java 開発者には通じにくい。`NOT_OVERRIDABLE` は JLS の
  「cannot be overridden」に沿う。
- 拡張の口 `TypeCandidateProvider#appliesToStaticBound()` と設定 `plugin.mapping.applies.to.static.bound` も
  改名する案。利用者が書いた拡張と設定を壊すので、名前は残し、javadoc に final も含むことを書いた。

### Q4. 利用者の手元への影響は

Excel のフィルタや後続のツールで `STATIC_BOUND:FINAL_*`・`OUTSIDE_METHOD`・`IMPLICIT_CTOR` を名指ししていれば、
新しい名前に直す必要がある。接頭辞（`RESOLVED:` / `UNRESOLVED:` / `EXTERNAL_USAGE:`）は変えていないので、
接頭辞で絞っている使い方には影響しない。
