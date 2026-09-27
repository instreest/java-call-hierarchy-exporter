// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jche.cache.CacheLock;

/**
 * test/incremental/run.sh の「同じキャッシュのフォルダを使う実行」の検査で、ほかの実行の代わりにキャッシュの
 * フォルダの錠（jche.cache.CacheLock）を持っておく。
 *
 * <pre>
 *   java LockHolder &lt;analysis-cache.tsv のパス&gt; &lt;持つミリ秒&gt; &lt;錠を取ったら作る印のファイル&gt;
 * </pre>
 * 錠を取ったら印のファイルを作り、決めた時間だけ持って放す。解析を始める前に印を待てば、解析は必ず錠を持たれた
 * 状態で始まる（時間に頼らない）。
 */
public final class LockHolder {

    private LockHolder() {
    }

    public static void main(String[] args) throws Exception {
        Path cacheFile = Paths.get(args[0]);
        long holdMillis = Long.parseLong(args[1]);
        Path marker = Paths.get(args[2]);
        try (CacheLock lock = CacheLock.acquire(cacheFile)) {
            Files.writeString(marker, lock.toString());
            Thread.sleep(holdMillis);
        }
    }
}
