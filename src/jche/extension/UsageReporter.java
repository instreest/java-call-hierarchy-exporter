// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

/**
 * 拡張が任意で実装する報告口: 解析の最後に「効いたか」を利用者へ知らせる。
 *
 * <p>対応表や条件を外から与える仕組みは、書き間違えても実行時は「当たらない」だけで何も言わない。
 * 設定したのに効いていないことに気づけるよう、拡張自身が使われた件数を知らせられるようにしてある
 * （同梱の {@link jche.builtin.TypeMappingProvider} がこれを実装している）。
 *
 * <p>{@link CallSiteHintCollector}（フェーズA）・{@link TypeCandidateProvider}（フェーズB）・
 * {@link ContractProvider} のどれと一緒に実装してもよい。拡張ポイントそのものではないので、
 * 実装しなくても何も起きない。
 *
 * <p>呼ばれるのはフェーズBの拡張だけである点に注意。フェーズAの拡張はキャッシュを再利用した実行では
 * そもそも動かないため、「0 件でした」と報告すると誤解を招く（docs/instance-analysis-plugin-qa.md の Q26）。
 */
public interface UsageReporter {

    /**
     * 利用状況を {@link jche.util.Log} に出す。CSV を書き終えたあとに 1 回だけ呼ばれる。
     *
     * <p>例外を投げても解析は止まらない（警告を出して飛ばす）。
     */
    void reportUsage();
}
