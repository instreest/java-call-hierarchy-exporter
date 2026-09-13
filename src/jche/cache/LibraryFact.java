// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * キャッシュを作ったときの依存 jar の1件（L行）。
 *
 * 呼び出し先・所有型・親型はバインディング解決の結果であり、依存 jar が変われば
 * ソースが同じでも変わりうる。そのため、どの jar に対して解析したかをキャッシュに残し、
 * 次回に jar の追加・変更・削除を検知して、影響するファイルだけを解析し直す
 * （{@link jche.analysis.CacheUpdater} 参照）。
 *
 * @param path     jar のパス。project.root 配下なら相対パス、外なら絶対パス（F行と同じ考え方）
 * @param size     サイズ（同一性の判定に使う。ソースファイルと同じ基準）
 * @param mtime    更新時刻（同上）
 * @param packages jar が含むクラスのパッケージ（重複なし・名前順）。jar が削除された後でも
 *                 「どのパッケージを参照していたファイルに影響するか」が分かるように持つ
 * @param hash     jar の内容ハッシュ（{@link jche.util.FileHash}）。更新時刻が変わってもサイズと内容が
 *                 同じなら「変わっていない」と判定するため。クラスフォルダ、または旧形式の行では空文字
 */
public record LibraryFact(String path, long size, long mtime, List<String> packages, String hash) {

    public LibraryFact {
        packages = List.copyOf(packages);
        hash = (hash == null) ? "" : hash;
    }

    /** 更新時刻だけを今の値に差し替えたもの（内容が同じと確かめた jar の L 行を、次回は更新時刻で通すため） */
    public LibraryFact withMtime(long newMtime) {
        return new LibraryFact(path, size, newMtime, packages, hash);
    }

    public String toRow() {
        return CacheFormat.joinRow("L", path, String.valueOf(size), String.valueOf(mtime),
                String.join(",", packages), hash);
    }

    /** 列が足りない、または数値が壊れていれば null */
    public static LibraryFact fromRow(String[] cols) {
        if (cols.length < 4) {
            return null;
        }
        long size;
        long mtime;
        try {
            size = Long.parseLong(cols[2]);
            mtime = Long.parseLong(cols[3]);
        } catch (NumberFormatException ignore) {
            return null;
        }
        List<String> packages = new ArrayList<>();
        String csv = CacheFormat.columnAt(cols, 4);
        if (!csv.isEmpty()) {
            for (String p : csv.split(",")) {
                if (!p.isEmpty()) {
                    packages.add(p);
                }
            }
        }
        return new LibraryFact(cols[1], size, mtime, packages, CacheFormat.columnAt(cols, 5));
    }
}
