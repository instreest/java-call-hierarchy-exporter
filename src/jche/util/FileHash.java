// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ファイル内容のハッシュ（SHA-256 の先頭 16 桁の 16 進）。
 *
 * キャッシュの差分判定は「更新時刻とサイズ」を基本にするが、更新時刻はファイルの中身と関係なく
 * 変わることがある（git のチェックアウト、コピー、CI のたびに作り直されるワークスペース）。
 * そのときにサイズが同じなら内容を突き合わせて、中身が同じファイルを「変更あり」と誤らないようにする
 * （{@link jche.analysis.CacheUpdater} / {@link jche.analysis.LibraryDiff}）。
 * 更新時刻とサイズが一致していれば計算しないので、通常の手元の実行では呼ばれない。
 */
public final class FileHash {

    private static final int HEX_LENGTH = 16;

    private FileHash() {
    }

    /** ファイルを読んでハッシュを求める。読めなければ IOException */
    public static String of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        byte[] d = digest.digest();
        StringBuilder sb = new StringBuilder(HEX_LENGTH);
        for (int i = 0; i < HEX_LENGTH / 2; i++) {
            sb.append(String.format("%02x", d[i]));
        }
        return sb.toString();
    }
}
