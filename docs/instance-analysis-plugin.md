# インスタンス解析条件を外から与える（プラグイン）

ソースを読むだけでは具象クラスが決まらない呼び出しを、外から与えた条件で 1 件に絞る仕組み。
設計上の判断と実装時に迷った点は
[instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) にある。

> **対応表を並べるだけで済むなら、拡張を読み込ませる必要はありません。**
> 契約表に 1 行書けば同じことができます
> （[callback-contracts.md の「C. 具象クラスを1件に絞る」](callback-contracts.md#c-具象クラスを1件に絞る)）。
>
> ```
> jp.co.xxx.dao.UserDao            => jp.co.xxx.dao.UserDaoImpl   # DI で注入されるフィールド
> jp.co.xxx.DaoFactory#get("USER") => jp.co.xxx.dao.UserDaoImpl   # ファクトリのキー
> ```
>
> ここで説明する拡張が要るのは、**キーが多すぎて表に並べたくない**（算出規則を書く）、
> **独自形式の設定ファイルを読む**、**列挙定数をキーにしている**場合です。経緯は
> [contracts-unification-design.md](contracts-unification-design.md) にあります。

DI コンテナで注入されるフィールドや、キーで実装を切り替えるファクトリメソッドは、ソースを読むだけでは
具象クラスが決まりません。既定ではインターフェースの実装を全部候補に挙げる（CHA）ため、
呼び出し階層が実装の数だけ枝分かれします。解決の条件を外から与えると、1 件に絞れます。

Spring の `@Autowired` などは注釈から自動で解決するので、設定は要りません
（[spring-di-qa.md](spring-di-qa.md)）。ここで扱うのは、**注釈からは分からない**もの
── XML や独自形式の DI 設定ファイル、キーで実装を切り替えるファクトリ、社内フレームワークの仕掛けです。
拡張は Spring の判定より先に効くので、自動の解決を上書きすることもできます。

解決は 2 つの段階に分かれています。必要な情報が手に入るタイミングが違うためです。

| 段階 | いつ | すること | 例 |
| --- | --- | --- | --- |
| フェーズA | ソースを読みながら | 呼び出し箇所の手がかりを拾う | `DaoFactory.get("USER_DAO")` の `"USER_DAO"` |
| フェーズB | グラフを組み立てるとき | 手がかりと宣言型から具象クラスを決める | `"USER_DAO"` → `jp.co.xxx.dao.UserDaoImpl` |

## 1. 対応表を書くだけで済ませる（同梱の実装を使う）

多くの場合、条件は「この宣言型（またはこの手がかり）のときは、この具象クラス」という対応表に落ちます。
その形なら Java を書く必要はありません。

```properties
# config/config.properties
resolver.hint.collectors=jche.builtin.FactoryKeyCollector
plugin.factory.methods=jp.co.xxx.DaoFactory#get
resolver.candidate.providers=jche.builtin.TypeMappingProvider
plugin.mapping.files=mapping.properties
```

```properties
# config/mapping.properties（UTF-8）
# 宣言型 -> 具象型（DI 設定から機械的に書き出せる形）
jp.co.xxx.dao.UserDao = jp.co.xxx.dao.UserDaoImpl
# 手がかり -> 具象型（区切りは @。properties では : と = が区切り文字なので使えない）
FACTORY_KEY@USER_DAO = jp.co.xxx.dao.UserDaoImpl
```

## 2. 自分で書く（設定ファイルの形式が独自、条件が複雑な場合）

`plugin.folders` のフォルダに `.java` を置くだけです。**実行時にコンパイルされる**ので、
Maven や Gradle でのビルドも jar 作りも要りません（すでに `.class` / `.jar` があるなら、それを置いても構いません）。

```properties
plugin.folders=plugins
resolver.candidate.providers=jp.co.xxx.MyDiProvider
```

```java
// config/plugins/MyDiProvider.java
package jp.co.xxx;

public class MyDiProvider implements jche.extension.TypeCandidateProvider {
    // init(Properties, Path) で独自形式の DI 設定ファイルを読み、
    // candidates(...) で具象クラスの FQN を返す
}
```

実装するインターフェースは `jche.extension.TypeCandidateProvider`（フェーズB）です。
**ファクトリに渡されたキーはツールが渡す**ので、それを拾うためだけに
`jche.extension.CallSiteHintCollector`（フェーズA）を書く必要はありません
（呼び出し箇所から独自の証拠を拾いたいときだけ使います）。動く例は
[test/regression/plugin/](../test/regression/plugin/)（設定・対応表・自前の拡張・期待出力）にあります。

- 具象クラスを拡張が決めた行は、`call-hierarchy.csv` の最終列に `[RESOLVED:<ラベル>]`（同梱の実装なら `MAPPING`）が付きます
- `candidates()` が返す型名は **単純名でもかまいません**（`UserDaoImpl`）。解析対象で 1 件に定まるときだけ使い、複数の型に当たるときは使わずに警告に出します
- フェーズAの拡張はキャッシュに手がかりを書くので、拡張やその設定・実装ファイルを変えると、
  キャッシュは自動的に捨てられて全件解析し直しになります（変え忘れによる古い結果の混入を防ぐため）
- 拡張の読み込み・コンパイルに失敗しても解析は止まりません。警告を出して拡張なしで続けます
- 対応表のどの行が引かれたかは、解析の最後に実行ログへ出ます（下記「効いているかを確かめる」）

## 3. 例: 文字列連結でクラス名を組み立てるファクトリ

次のように、キーからクラス名を**算出**するファクトリは対応表にも拡張にも向く典型です。
文字列の連結や加工は静的解析では追えないため（[static-analysis-limits.md](static-analysis-limits.md)）、
既定では候補が実装の数だけ並びます。

```java
// 解析対象のプロジェクト側
public final class ServiceFactory {

    private static final Map<String, Service> POOL = new HashMap<>();

    public static Service get(String key) {
        String fqn = "jp.co.app.impl." + capitalize(key) + "Service";
        Service cached = POOL.get(fqn);
        if (cached != null) {
            return cached;
        }
        Service created = (Service) Class.forName(fqn).getDeclaredConstructor().newInstance();
        POOL.put(fqn, created);
        return created;
    }
}
```

```java
public void run() {
    Service s = ServiceFactory.get("user");
    s.execute();

    ServiceFactory.get("order").execute();
}
```

拡張なしで解析すると、`execute()` の呼び出しは絞れず、その先（`UserService.audit` など）へも降りません。

```csv
at jp.co.app.Main.run(Main.java:6),OrderService.execute,Main.run,OrderService.execute,[UNEXPANDED:CHA] 候補2件: ローカル変数
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,[UNEXPANDED:CHA] 候補2件: ローカル変数
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,[UNEXPANDED:CHA] 候補2件: 戻り値（ファクトリメソッド等）
at jp.co.app.Main.run(Main.java:8),UserService.execute,Main.run,UserService.execute,[UNEXPANDED:CHA] 候補2件: 戻り値（ファクトリメソッド等）
```

どちらの手段でも、フェーズA（キーの採取）は同梱の `FactoryKeyCollector` に任せられます。
`get("user")` の実引数の**最初の文字列リテラル**を、その戻り値を受けている変数（変数に受けずに
そのまま呼ぶ形なら式そのもの）に結び付けて残します。**算出後の FQN ではなく、呼び出し箇所に
書かれている生のキー**が証拠になるので、解析器が文字列演算を再現する必要はありません。

### 3a. 対応表で済ませる

キーが列挙できるなら、Java は要りません。

```properties
# config.properties
resolver.hint.collectors=jche.builtin.FactoryKeyCollector
plugin.factory.methods=jp.co.app.ServiceFactory#get
resolver.candidate.providers=jche.builtin.TypeMappingProvider
plugin.mapping.files=mapping.properties
```

```properties
# mapping.properties
FACTORY_KEY@user  = jp.co.app.impl.UserService
FACTORY_KEY@order = jp.co.app.impl.OrderService
```

```csv
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,[RESOLVED:MAPPING]
at jp.co.app.impl.UserService.execute(UserService.java:8),UserService.audit,Main.run,UserService.execute,UserService.audit
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,[RESOLVED:MAPPING]
at jp.co.app.impl.OrderService.execute(OrderService.java:8),OrderService.settle,Main.run,OrderService.execute,OrderService.settle
```

1件に確定し、そこから先（`audit` / `settle`）へも降りるようになります。

### 拡張に渡る証拠（`Hint`）

`candidates()` が受け取る `hints` には、次の 3 つがツールから自動で入ります
（レシーバがファクトリメソッドの戻り値だった場合）。

| `kind` | `value` | 例 |
|---|---|---|
| `Hint.KIND_FACTORY`（`"FACTORY"`） | ファクトリのメソッド（`型FQN#メソッド名`）。実装が親クラスにあるときは**ソースに書いた型**と**宣言元の型**の両方が入る | `jp.co.app.ChildFactory#pick` と `jp.co.app.BaseFactory#pick` |
| `Hint.KIND_FACTORY_KEY`（`"FACTORY_KEY"`） | 渡された文字列のキー（定数は値まで評価済み） | `user` |
| `Hint.KIND_FACTORY_CONST`（`"FACTORY_CONST"`） | 渡された列挙定数 | `jp.co.app.Kind.USER` |

フェーズAの拡張が残した証拠があれば、それも同じリストに並びます（種別は拡張が決めた名前）。

### 3b. 算出規則を書く（自前の拡張）

キーが多くて対応表を並べたくない、あるいはキーが増えるたびに表を直したくない場合は、
**算出規則そのもの**をフェーズBの拡張に書きます。`plugin.folders` に `.java` を置くだけで、
実行時にコンパイルされます（Maven / Gradle でのビルドも jar 作りも不要）。

```properties
# config.properties — 書くのはこの 2 行と、拡張が自分で読む設定だけ
plugin.folders=plugins
resolver.candidate.providers=demo.NamingConventionProvider
demo.naming.prefix=jp.co.app.impl.
demo.naming.suffix=Service
```

> **ファクトリのキーを拾うための設定は要りません。** キーはデータフローの値グラフに載っているので、
> ツールが証拠（`Hint`）にして拡張へ渡します。フェーズAの拡張（`resolver.hint.collectors` と
> `plugin.factory.methods`）を書く必要があるのは、**呼び出し箇所から独自の証拠を拾いたいとき**だけです。

```java
// config/plugins/NamingConventionProvider.java
package demo;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;

/**
 * ファクトリが文字列連結で組み立てるクラス名の「算出規則」を、解析器の代わりに書く。
 *
 *   String fqn = "jp.co.app.impl." + capitalize(key) + "Service";
 */
public class NamingConventionProvider implements TypeCandidateProvider {

    private String kind;
    private String prefix;
    private String suffix;

    @Override
    public void init(Properties config, Path configDir) {
        // キー名は拡張の作者が決めてよい。解析対象ごとに設定ファイルで変えられるようにしておく
        this.kind = config.getProperty("demo.naming.kind", "FACTORY_KEY").trim();
        this.prefix = config.getProperty("demo.naming.prefix", "").trim();
        this.suffix = config.getProperty("demo.naming.suffix", "").trim();
    }

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        for (Hint hint : hints) {
            if (!kind.equals(hint.kind())) {
                continue;
            }
            // FactoryKeyCollector が拾った生のキー（"user"）から FQN を組み立てる
            return new String[] {prefix + capitalize(hint.value()) + suffix};
        }
        return null;   // 証拠が無ければ何も言わない（CHA に任せる）
    }

    @Override
    public String label() {
        return "NAMING_CONVENTION";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
```

```csv
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,[RESOLVED:NAMING_CONVENTION]
at jp.co.app.impl.UserService.execute(UserService.java:8),UserService.audit,Main.run,UserService.execute,UserService.audit
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,[RESOLVED:NAMING_CONVENTION]
at jp.co.app.impl.OrderService.execute(OrderService.java:8),OrderService.settle,Main.run,OrderService.execute,OrderService.settle
```

### 3c. 「このファクトリのときだけ」という条件の書き方

`hints` には **どのファクトリから来た値か**（`Hint.KIND_FACTORY`）が入るので、そのまま条件になります。

```java
private static final String FACTORY = "jp.co.app.ServiceFactory#get";

@Override
public String[] candidates(String declaredType, String signature, List<Hint> hints) {
    if (!hints.contains(new Hint(Hint.KIND_FACTORY, FACTORY))) {
        return null;                          // ← このファクトリ以外には何も言わない
    }
    for (Hint hint : hints) {
        if (Hint.KIND_FACTORY_KEY.equals(hint.kind())) {
            return new String[] {"jp.co.app.impl." + capitalize(hint.value()) + "Service"};
        }
    }
    return null;
}
```

| 状況 | 条件の書き方 |
|---|---|
| 対象のファクトリが 1 つ | `Hint.KIND_FACTORY` と突き合わせる（上の例） |
| ファクトリごとに戻り値の型が違う | `declaredType`（呼び出し先を宣言している型の FQN）で分けてもよい |
| 同じ型を返すファクトリが複数あって規則が違う | `Hint.KIND_FACTORY` の値を規則表の鍵にする |

最後の形は、ファクトリのメソッドキーをそのまま鍵にできます。

```properties
plugin.folders=plugins
resolver.candidate.providers=demo.PerFactoryProvider
demo.rules=jp.co.app.ServiceFactory#get|jp.co.app.impl.|Service, \
           jp.co.app.LegacyFactory#create|jp.co.app.legacy.|ServiceImpl
```

```java
/** "型FQN#メソッド名" -> {接頭辞, 接尾辞} を init で読んでおく */
private final Map<String, String[]> rules = new LinkedHashMap<>();

@Override
public String[] candidates(String declaredType, String signature, List<Hint> hints) {
    String[] rule = null;
    String key = null;
    for (Hint hint : hints) {
        if (Hint.KIND_FACTORY.equals(hint.kind())) {
            rule = rules.get(hint.value());        // ← ここが「このファクトリのとき」の条件
        } else if (Hint.KIND_FACTORY_KEY.equals(hint.kind()) && key == null) {
            key = hint.value();
        }
    }
    return (rule == null || key == null)
            ? null : new String[] {rule[0] + capitalize(key) + rule[1]};
}
```

`ServiceFactory.get("user")` と `LegacyFactory.create("user")` が同じ `Service` を返していても、
呼んだファクトリごとに別の実装へ解決できます。

> 以前はこの場合分けのために、**自前のフェーズA拡張を書いて証拠の種別にファクトリ名を埋める**
> 必要がありました（`sink.add(scopeKey, "FACTORY:" + target, key)`）。いまはツールが
> `Hint.KIND_FACTORY` で渡すので要りません。

#### 証拠は1件とは限らない

フェーズAの拡張が拾った証拠は「呼び出しのレシーバ」ごとに溜まります。同じ変数に複数のファクトリから
代入されると、その変数を使う呼び出しには**証拠が複数付きます**。

```java
Service s = ServiceFactory.get("user");
if (flag) {
    s = LegacyFactory.create("user");
}
s.execute();            // ← 証拠が2件付く
```

ここで最初に一致した証拠だけを返すと、**もう一方の経路の実装が黙って消えます**
（`UserServiceImpl.execute` とその先が出力から無くなる）。一致した証拠は全部返してください。

複数返した呼び出しは「絞れていない」扱いになり、注記が `[UNEXPANDED:CHA] 候補N件` になってその先へは
降りません。**1件に絞れたときだけ展開される**、という点は本体の判定と同じです。
降りないのは痛いですが、実装が1つ消えるよりは安全です。

### 3d. 書くときの注意

- **返す FQN は具象クラスでかまいません。** そのメソッドを自分では宣言せず親から継承している型
  （`class ReportService extends AbstractService {}`）を返しても、本体を持つ親の宣言まで辿って
  解決します。`call-hierarchy.csv` には実際に動く実装（この例では `AbstractService.execute`）が出ます
- **候補を1件も採用できないときは警告が出ます。** その型にも親にもそのシグネチャの本体が無い場合
  （FQN の打ち間違い、解析対象外の型、シグネチャ違い）です。候補を落として CHA に戻るだけなので
  呼び出しは漏れませんが、対応表が効いていないことに気づけるよう実行ログに1回出します
- **キーを定数で渡している場合**（`get(Keys.USER)`）、`FactoryKeyCollector` は定数の**単純名**
  （`USER`）を証拠にします。定数の値までは追わないので、対応表の左辺や拡張側の判定も単純名で書きます
- **`candidates` が候補を返せないときは `null` を返す。** 誤った型を1件返すより、CHA に候補を
  並べさせるほうが安全です（絞れないことより誤って絞ることの方が害が大きい）
- 拡張の中で例外を投げても解析は止まりません。警告を出してその拡張を飛ばします
- `resolver.candidate.providers` に複数書いた場合は、**先に候補を返した拡張が勝ちます**

---

## 効いているかを確かめる

対応表も契約表と同じで、左辺を間違えても実行時は「引かれない」だけで出力は黙って元のままです。
同梱の `TypeMappingProvider` は、解析の最後にどの行が引かれたかを実行ログに出します。

```
[plugin] TypeMappingProvider: 対応表の適用 4/5 行
[WARN] 対応表で一度も引かれなかった行が 1 件あります。左辺の綴り違いか、その呼び出しが先の段（実装が1つ・その場で new 等）で既に絞れている可能性があります:
    FACTORY_KEY@NO_SUCH_KEY
```

引かれなかった原因は 2 つあり、どちらかは機械的に決められないので両方を挙げています。

| 原因 | どうするか |
|---|---|
| 左辺の綴り違い（証拠の種別・キー・宣言型の FQN） | 直す。証拠のキーは `plugin.factory.hint.kind` の値と `@` でつないだ形（既定 `FACTORY_KEY@<キー>`） |
| その呼び出しが[段1・段2](../README.md#具象クラスの解決)で既に絞れていて、拡張まで来ていない | そのままでよい。対応表を書く前から 1 件に絞れていたということ |

自前のフェーズB拡張でも同じ知らせを出せます。`jche.extension.UsageReporter` を一緒に実装すると、
CSV を書き終えたあとに `reportUsage()` が 1 回呼ばれます（実装しなくても何も起きません）。

```java
public class MyDiProvider implements TypeCandidateProvider, jche.extension.UsageReporter {
    @Override
    public void reportUsage() {
        jche.util.Log.info("[plugin] MyDiProvider: " + used + "/" + rules.size() + " 件の規則が効きました");
    }
}
```

フェーズA（`CallSiteHintCollector`）では呼ばれません。キャッシュを再利用した実行ではフェーズAが
そもそも動かないため、「0 件でした」と報告すると誤解を招くからです。
