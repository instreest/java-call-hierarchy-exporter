# 計測用（テストではない）

`bash test/*/run.sh` のような合否の検査ではなく、**性能を測るための道具**を置く場所。
CI では動かさない。使い方と、ここで測った結果の読み方は
[docs/ast-analysis-performance-qa.md](../../docs/ast-analysis-performance-qa.md) の
「計測のしかた」にある。

| ファイル | 役割 |
|---|---|
| `JfrReport.java` | JFR の記録を**時間帯で切って**集計する（`jfr print` / `jfr view` に時間帯の指定が無いため）。待ち・停止の内訳、ヒープの最大と累計割り当て量、処理時間とメモリの「機能内訳」を出す |
| `RetainedHeap.java` | 解析結果（フェーズ 2 の終わりの `AnalysisSnapshot`）が持ち続けるヒープを、完全 GC の前後の差で測る。版を変えて比べるとき用（使い方はファイルの先頭。測った結果は [docs/cache-unification-qa.md](../../docs/cache-unification-qa.md) の Q17） |
