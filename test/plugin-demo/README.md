# test/plugin-demo

回帰テストの `plugin` ケース（`test/regression/plugin/`）が解析する小さな Java プロジェクトです。
**ソースを読むだけでは具象クラスが決まらない呼び出し**だけを集めてあります。動くプログラムとしての意味はありません。

`fxp.App` の 3 つのメソッドが、それぞれ別の経路を踏みます。

| メソッド | 書き方 | 拡張なし | 拡張あり |
| --- | --- | --- | --- |
| `factoryCall()` | `Dao dao = DaoFactory.get("USER_DAO"); dao.find();` | `Dao` の実装 2 件に広がる | `UserDaoImpl` 1 件 |
| `chainedCall()` | `DaoFactory.get("ORDER_DAO").find();` | 同上 | `OrderDaoImpl` 1 件 |
| `injected()` | `service.run();`（DI で注入されるフィールド） | `Service` の実装 2 件に広がる | 対応表・DI 設定ファイル次第で 1 件 |

`factoryCall()` はファクトリの実引数（手がかり）がローカル変数への代入を経て呼び出しの受け手に届く経路、
`chainedCall()` は変数に受けず、受け手の式そのものがファクトリの呼び出しである経路です。どちらもツールが
値グラフ（キャッシュの `N` 行）からファクトリの実引数を読み（[FactoryCalls](../../src/jche/graph/FactoryCalls.java)）、
拡張（`TypeCandidateProvider`）には読んだ値が証拠として渡ります。拡張が AST から証拠を残す口は無くなりました
（[docs/instance-analysis-plugin-qa.md](../../docs/instance-analysis-plugin-qa.md) の Q28）。

インターフェース（`Dao` / `Service`）にはそれぞれ実装が 2 つあります。1 つだと単一実装ショートカットで
解決してしまい、拡張の効き目が見えないためです。
