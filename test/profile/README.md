# 計測用（テストではない）

`bash test/*/run.sh` のような合否の検査ではなく、**性能を測るための道具**を置く場所。
CI では動かさない。使い方と、ここで測った結果の読み方は
[docs/ast-analysis-performance-qa.md](../../docs/ast-analysis-performance-qa.md) の
「計測のしかた」にある。

| ファイル | 役割 |
|---|---|
| `JfrReport.java` | JFR の記録を**時間帯で切って**集計する（`jfr print` / `jfr view` に時間帯の指定が無いため）。待ち・停止の内訳、ヒープの最大と累計割り当て量、処理時間とメモリの「機能内訳」を出す |
