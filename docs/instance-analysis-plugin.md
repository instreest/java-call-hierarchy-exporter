# インスタンス解析条件を外から与える（プラグイン）

ソースを読むだけでは具象クラスが決まらない呼び出しを、外から与えた条件で 1 件に絞る仕組み。
設計上の判断と実装時に迷った点は
[instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) にある。

> ここで書く対応表を、契約表（[callback-contracts.md](callback-contracts.md)）の 1 行にまとめる案が
> [contracts-unification-design.md](contracts-unification-design.md) にある（未実装）。

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

- 具象クラスを拡張が決めた行は、`call-hierarchy.csv` の最終列に `[RESOLVED:<ラベル>]`（同梱の実装なら `MAPPING`）が付きます
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
at jp.co.app.Main.run(Main.java:6),UserService.execute,Main.run,UserService.execute,[RESOLVED:NAMING_CONVENTION]
at jp.co.app.impl.UserService.execute(UserService.java:8),UserService.audit,Main.run,UserService.execute,UserService.audit
at jp.co.app.Main.run(Main.java:8),OrderService.execute,Main.run,OrderService.execute,[RESOLVED:NAMING_CONVENTION]
at jp.co.app.impl.OrderService.execute(OrderService.java:8),OrderService.settle,Main.run,OrderService.execute,OrderService.settle
```

### 3c. 「このファクトリのときだけ」という条件の書き方

`candidates` に渡るのは、**解決しようとしている呼び出しの情報**（`declaredType` / `signature`）と
証拠のリストだけで、**どのファクトリから来た値かは渡りません**。条件は次の3つのどれかで書きます。

| 状況 | 条件の書き方 | 自前のフェーズA拡張 |
|---|---|---|
| 対象のファクトリが1つ | 書かなくてよい。`plugin.factory.methods` が既に絞っている | 不要 |
| ファクトリごとに戻り値の型が違う | `declaredType` で分ける | 不要 |
| 同じ型を返すファクトリが複数あって規則が違う | **証拠の種別**（`Hint.kind`）で分ける | 必要 |

#### 条件を書かなくてよい場合

`plugin.factory.methods=jp.co.app.ServiceFactory#get` と書いた時点で、証拠が付くのは
**そのファクトリの戻り値を受けている呼び出しだけ**です。`hints` に `FACTORY_KEY` が入っていること
自体が「このファクトリから来た」という条件になっているので、3b の例のように種別だけ見れば足ります。

#### 宣言型で分ける

戻り値の型がファクトリごとに違うなら、`declaredType`（呼び出し先を宣言している型の FQN。
`s.execute()` なら `jp.co.app.Service`）で規則を切り替えられます。フェーズAは同梱の実装のままで済みます。

```properties
resolver.hint.collectors=jche.builtin.FactoryKeyCollector
plugin.factory.methods=jp.co.app.ServiceFactory#get, jp.co.app.DaoFactory#lookup
resolver.candidate.providers=demo.ByDeclaredTypeProvider
demo.rules=jp.co.app.Service|jp.co.app.impl.|Service, jp.co.app.Dao|jp.co.app.dao.|Dao
```

```java
// 宣言型FQN -> {接頭辞, 接尾辞} の rules を init で読んでおく（組み立ては下の PerFactoryProvider と同じ）
@Override
public String[] candidates(String declaredType, String signature, List<Hint> hints) {
    String[] rule = rules.get(declaredType);     // ← ここが条件
    if (rule == null) {
        return null;
    }
    for (Hint hint : hints) {
        if ("FACTORY_KEY".equals(hint.kind())) {
            return new String[] {rule[0] + capitalize(hint.value()) + rule[1]};
        }
    }
    return null;
}
```

同梱の `FactoryKeyCollector` は種別を1つしか持たない（`plugin.factory.hint.kind`。既定 `FACTORY_KEY`）ため、
複数のファクトリを並べても証拠は同じ種別になります。**規則が違うファクトリを並べたまま宣言型を見ないと、
別のファクトリのキーに誤った規則を当ててしまいます。** 組み立てた FQN が解析対象に無ければ候補は
採用されず CHA に戻るだけですが、たまたま実在する型名になった場合は**誤った1件に確定します**。

#### 証拠の種別で分ける（同じ型を返すファクトリが複数）

`ServiceFactory.get("user")` と `LegacyFactory.create("user")` がどちらも `Service` を返し、
組み立て規則だけが違う場合、宣言型もキーも同じなので区別できません。この場合は
**「どのファクトリから来たか」を証拠の種別に入れる**フェーズA拡張を書きます。

```properties
plugin.folders=plugins
resolver.hint.collectors=demo.FactoryScopedKeyCollector
demo.factory.methods=jp.co.app.ServiceFactory#get, jp.co.app.LegacyFactory#create
resolver.candidate.providers=demo.PerFactoryProvider
demo.rules=jp.co.app.ServiceFactory#get|jp.co.app.impl.|Service, jp.co.app.LegacyFactory#create|jp.co.app.legacy.|ServiceImpl
```

```java
// config/plugins/FactoryScopedKeyCollector.java
package demo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;

import jche.extension.CallSiteHintCollector;
import jche.extension.HintKeys;
import jche.extension.HintSink;

/**
 * フェーズA: 「どのファクトリメソッドから来た値か」を証拠の種別に入れて残す。
 *
 * 同梱の FactoryKeyCollector は種別が1つ（既定 FACTORY_KEY）なので、
 * 同じ宣言型を返すファクトリが複数あって規則が違う場合に区別できない。
 * 種別を "FACTORY:<型FQN>#<メソッド名>" にすれば、フェーズBで条件が書ける。
 */
public class FactoryScopedKeyCollector implements CallSiteHintCollector {

    private final List<String> targets = new ArrayList<>();

    @Override
    public void init(Properties config, Path configDir) {
        for (String one : config.getProperty("demo.factory.methods", "").split(",")) {
            String t = one.trim();
            if (!t.isEmpty()) {
                targets.add(t);
            }
        }
    }

    @Override
    public void collect(MethodInvocation node, CompilationUnit cu, String callerMethodKey, HintSink sink) {
        String target = matched(node);
        if (target == null) {
            return;
        }
        String key = firstStringLiteral(node);
        if (key == null) {
            return;
        }
        String scopeKey = HintKeys.ofAssignedVariable(node);
        if (scopeKey.isEmpty()) {
            scopeKey = HintKeys.ofPosition(node);
        }
        sink.add(scopeKey, "FACTORY:" + target, key);
    }

    /** 呼び出しが対象のファクトリメソッドなら "型FQN#メソッド名"。違えば null */
    private String matched(MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (binding == null) {
            return null;
        }
        ITypeBinding declaring = binding.getDeclaringClass();
        if (declaring == null) {
            return null;
        }
        String key = declaring.getErasure().getQualifiedName() + "#" + node.getName().getIdentifier();
        return targets.contains(key) ? key : null;
    }

    private static String firstStringLiteral(MethodInvocation node) {
        for (Object o : node.arguments()) {
            if (o instanceof StringLiteral literal) {
                return literal.getLiteralValue();
            }
        }
        return null;
    }
}
```

```java
// config/plugins/PerFactoryProvider.java
package demo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;

/** フェーズB: ファクトリメソッドごとに算出規則を切り替える */
public class PerFactoryProvider implements TypeCandidateProvider {

    /** "FACTORY:<型FQN>#<メソッド名>" -> {接頭辞, 接尾辞} */
    private final Map<String, String[]> rules = new LinkedHashMap<>();

    @Override
    public void init(Properties config, Path configDir) {
        for (String one : config.getProperty("demo.rules", "").split(",")) {
            String t = one.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] p = t.split("\\|", -1);
            rules.put("FACTORY:" + p[0].trim(), new String[] {p[1].trim(), p[2].trim()});
        }
    }

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        // 証拠は1件とは限らない（同じ変数に複数のファクトリから代入されうる）。
        // 先頭で return すると、もう一方の経路の実装が黙って消えるので全部返す
        List<String> out = new ArrayList<>(1);
        for (Hint hint : hints) {
            String[] rule = rules.get(hint.kind());     // ← ここが「このファクトリのとき」の条件
            if (rule == null) {
                continue;
            }
            String fqn = rule[0] + capitalize(hint.value()) + rule[1];
            if (!out.contains(fqn)) {
                out.add(fqn);
            }
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    @Override
    public String label() {
        return "PER_FACTORY";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
```

同じキー `"user"`・同じ宣言型 `Service` でも、呼び出したファクトリごとに別の実装へ解決します。

```csv
at jp.co.app.Main.run(Main.java:5),UserService.execute,Main.run,UserService.execute,[RESOLVED:PER_FACTORY]
at jp.co.app.impl.UserService.execute(UserService.java:4),UserService.modern,Main.run,UserService.execute,UserService.modern
at jp.co.app.Main.run(Main.java:6),UserServiceImpl.execute,Main.run,UserServiceImpl.execute,[RESOLVED:PER_FACTORY]
at jp.co.app.legacy.UserServiceImpl.execute(UserServiceImpl.java:4),UserServiceImpl.legacy,Main.run,UserServiceImpl.execute,UserServiceImpl.legacy
```

#### 証拠は1件とは限らない

証拠は「呼び出しのレシーバ」ごとに溜まります。同じ変数に複数のファクトリから代入されると、
その変数を使う呼び出しには**証拠が複数付きます**。

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
