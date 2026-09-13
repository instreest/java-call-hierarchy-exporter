# test/plugin-demo

回帰テストの `plugin` ケース（`test/regression/plugin/`）が解析する小さな Java プロジェクトです。
**ソースを読むだけでは具象クラスが決まらない呼び出し**だけを集めてあります。動くプログラムとしての意味はありません。

`fxp.App` の 3 つのメソッドが、それぞれ別の経路を踏みます。

| メソッド | 書き方 | 拡張なし | 拡張あり |
| --- | --- | --- | --- |
| `factoryCall()` | `Dao dao = DaoFactory.get("USER_DAO"); dao.find();` | `Dao` の実装 2 件に広がる | `UserDaoImpl` 1 件 |
| `chainedCall()` | `DaoFactory.get("ORDER_DAO").find();` | 同上 | `OrderDaoImpl` 1 件 |
| `injected()` | `service.run();`（DI で注入されるフィールド） | `Service` の実装 2 件に広がる | 対応表・DI 設定ファイル次第で 1 件 |

`factoryCall()` は手がかりがローカル変数に結び付く経路、`chainedCall()` は変数に受けないため
式の位置に結び付く経路で、拡張が証拠を残すキーの作り方が違います
（[HintKeys](../../src/jche/extension/HintKeys.java)）。

インターフェース（`Dao` / `Service`）にはそれぞれ実装が 2 つあります。1 つだと単一実装ショートカットで
解決してしまい、拡張の効き目が見えないためです。
