// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * キャッシュを作ったときの依存 jar（またはクラスフォルダ）の1件（L行）。
 *
 * 呼び出し先・所有型・親型はバインディング解決の結果であり、依存 jar が変われば
 * ソースが同じでも変わりうる。そのため、どの jar に対して解析したかをキャッシュに残し、
 * 次回に jar の追加・変更・削除を検知して、影響するファイルだけを解析し直す
 * （{@link jche.analysis.CacheUpdater} 参照）。
 *
 * <p>同一性は指紋だけで見る。更新時刻もサイズも持たないのは、ソースファイル（F行）と同じ理由で、
 * 更新時刻が中身と関係なく変わるため（{@link jche.analysis.LibraryDiff} が指紋の作り方を持つ）。
 *
 * @param path        jar のパス。project.root 配下なら相対パス、外なら絶対パス（F行と同じ考え方）
 * @param fingerprint 中身の指紋。jar なら中の一覧（名前・サイズ・CRC）の、クラスフォルダなら
 *                    {@code .class} の一覧（相対パス・サイズ・内容ハッシュ）のハッシュ。
 *                    読み取れなかった場合は空文字で、そのときは毎回「変わった」とみなされる（安全側）
 * @param packages    jar が含むクラスのパッケージ（重複なし・名前順）。jar が削除された後でも
 *                    「どのパッケージを参照していたファイルに影響するか」が分かるように持つ。
 *                    無名パッケージ（デフォルトパッケージ）のクラスは {@link #UNNAMED_PACKAGE} で表す
 */
public record LibraryFact(String path, String fingerprint, List<String> packages) {

    /**
     * 無名パッケージ（デフォルトパッケージ）を表す名前。パッケージの名前には使えない文字（{@code <}）を含むので、
     * 本物のパッケージと取り違えない。空文字にしないのは、パッケージの一覧（L 行の 4 列目。カンマ区切り）で
     * 空の要素は読み飛ばすため（{@link #fromRow}）。
     *
     * <p>無名パッケージのクラスは、ほかのパッケージのソースからは参照できないが、無名パッケージのソースからは
     * 参照できる（JLS 7.4.2）。以前は除いていたので、jar の無名パッケージのクラスを変えても、それを使う無名パッケージの
     * ソースを解析し直さなかった
     */
    public static final String UNNAMED_PACKAGE = "<unnamed>";

    public LibraryFact {
        fingerprint = (fingerprint == null) ? "" : fingerprint;
        packages = List.copyOf(packages);
    }

    /** 中身を読み取れたか。読み取れていなければ同一性を判定できない */
    public boolean known() {
        return !fingerprint.isEmpty();
    }

    public String toRow() {
        return CacheFormat.joinRow("L", path, fingerprint, String.join(",", packages));
    }

    /** 列が足りなければ null */
    public static LibraryFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        List<String> packages = new ArrayList<>();
        String csv = CacheFormat.columnAt(cols, 3);
        if (!csv.isEmpty()) {
            for (String p : csv.split(",")) {
                if (!p.isEmpty()) {
                    packages.add(p);
                }
            }
        }
        return new LibraryFact(cols[1], cols[2], packages);
    }
}
