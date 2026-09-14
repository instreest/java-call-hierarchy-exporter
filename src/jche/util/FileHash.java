// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ファイル内容のハッシュ（SHA-256 の先頭 16 桁の 16 進）。
 *
 * キャッシュが「同じファイルか」を決める最後の手段。更新時刻は中身と関係なく変わる
 * （git のチェックアウト、コピー、CI のたびに作り直されるワークスペース）ので使わず、
 * パスとサイズと、この内容ハッシュだけで見る
 * （{@link jche.analysis.CacheUpdater} / {@link jche.analysis.LibraryDiff}）。
 *
 * <p>計算は 1 ファイル 1 回で、サイズが違えば呼ばれない。SHA-256 の先頭 16 桁に切るのは、
 * キャッシュの 1 行を短く保つため。衝突しても困るのは「中身が違うのに同じとみなす」ときだけで、
 * サイズも一致している必要があるため、実用上は起きない。
 */
public final class FileHash {

    private static final int HEX_LENGTH = 16;

    private FileHash() {
    }

    /**
     * 文字列のハッシュ。ファイルと同じ形（SHA-256 の先頭 16 桁）。
     *
     * 長い定数の値をそのままキャッシュに持たずに、変化だけを見るために使う
     * （{@link jche.cache.ConstantFact}）
     */
    public static String ofText(String text) {
        return hex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(HEX_LENGTH);
        for (int i = 0; i < HEX_LENGTH / 2; i++) {
            sb.append(String.format("%02x", d[i]));
        }
        return sb.toString();
    }

    /** ファイルを読んでハッシュを求める。読めなければ IOException */
    public static String of(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }
}
