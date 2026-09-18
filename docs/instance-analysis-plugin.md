# インスタンス解析条件を外から与える（プラグイン）

ソースを読むだけでは具象クラスが決まらない呼び出しを、外から与えた条件で 1 件に絞る仕組み。
設計上の判断と実装時に迷った点は
[instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) にある。

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

実装するインターフェースは `jche.extension.CallSiteHintCollector`（フェーズA）と
`jche.extension.TypeCandidateProvider`（フェーズB）です。動く例は
[test/regression/plugin/](../test/regression/plugin/)（設定・対応表・自前の拡張・期待出力）にあります。

- 具象クラスを拡張が決めた行は、`call-hierarchy.csv` の最終列に `解決:<ラベル>`（同梱の実装なら `MAPPING`）が付きます
- フェーズAの拡張はキャッシュに手がかりを書くので、拡張やその設定・実装ファイルを変えると、
  キャッシュは自動的に捨てられて全件解析し直しになります（変え忘れによる古い結果の混入を防ぐため）
- 拡張の読み込み・コンパイルに失敗しても解析は止まりません。警告を出して拡張なしで続けます

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
at jp.co.app.Main.run(Main.java:6),OrderService.execute,Main.run,OrderService.execute,CHA候補2件（未展開）: ローカル変数
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,CHA候補2件（未展開）: ローカル変数
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,CHA候補2件（未展開）: 戻り値（ファクトリメソッド等）
at jp.co.app.Main.run(Main.java:8),UserService.execute,Main.run,UserService.execute,CHA候補2件（未展開）: 戻り値（ファクトリメソッド等）
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
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,解決:MAPPING
at jp.co.app.impl.UserService.execute(UserService.java:8),UserService.audit,Main.run,UserService.execute,UserService.audit
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,解決:MAPPING
at jp.co.app.impl.OrderService.execute(OrderService.java:8),OrderService.settle,Main.run,OrderService.execute,OrderService.settle
```

1件に確定し、そこから先（`audit` / `settle`）へも降りるようになります。

### 3b. 算出規則を書く（自前の拡張）

キーが多くて対応表を並べたくない、あるいはキーが増えるたびに表を直したくない場合は、
**算出規則そのもの**をフェーズBの拡張に書きます。`plugin.folders` に `.java` を置くだけで、
実行時にコンパイルされます（Maven / Gradle でのビルドも jar 作りも不要）。

```properties
# config.properties
plugin.folders=plugins

# フェーズA: 同梱の実装をそのまま使う（キーの採取だけなので自分で書く必要は無い）
resolver.hint.collectors=jche.builtin.FactoryKeyCollector
plugin.factory.methods=jp.co.app.ServiceFactory#get

# フェーズB: 算出規則を書いた自前の拡張
resolver.candidate.providers=demo.NamingConventionProvider
demo.naming.prefix=jp.co.app.impl.
demo.naming.suffix=Service
```

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
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,解決:NAMING_CONVENTION
at jp.co.app.impl.UserService.execute(UserService.java:8),UserService.audit,Main.run,UserService.execute,UserService.audit
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,解決:NAMING_CONVENTION
at jp.co.app.impl.OrderService.execute(OrderService.java:8),OrderService.settle,Main.run,OrderService.execute,OrderService.settle
```

### 3c. 書くときの注意

- **返す FQN は、そのメソッドを宣言している型にする。** 候補は「FQN ＋ 呼び出し先のシグネチャ」で
  引かれるため、`class ReportService extends AbstractService {}` のように `execute()` を継承していて
  自分では宣言していない型を返すと、候補として採用されず、**警告も出さずに** CHA に戻ります
  （候補を落とすだけなので呼び出しは漏れませんが、絞れていないことに気づきにくい）。
  この例では `jp.co.app.impl.AbstractService` を返せば解決します
- **キーを定数で渡している場合**（`get(Keys.USER)`）、`FactoryKeyCollector` は定数の**単純名**
  （`USER`）を証拠にします。定数の値までは追わないので、対応表の左辺や拡張側の判定も単純名で書きます
- **`candidates` が候補を返せないときは `null` を返す。** 誤った型を1件返すより、CHA に候補を
  並べさせるほうが安全です（絞れないことより誤って絞ることの方が害が大きい）
- 拡張の中で例外を投げても解析は止まりません。警告を出してその拡張を飛ばします
- `resolver.candidate.providers` に複数書いた場合は、**先に候補を返した拡張が勝ちます**
