// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.ByteArrayOutputStream;
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
     * （{@link jche.cache.ConstantFact}。自分の宣言の指紋もこれで作る）。
     *
     * <h4>文字列を 1 文字も失わずにバイト列にする</h4>
     * {@code String#getBytes(UTF_8)} は、対になっていないサロゲート（"&#92;uD800"）を {@code '?'} に置き換える。
     * すると "&#92;uD800" と "&#92;uD801" が同じハッシュになり、定数の値が変わったのに差分更新が使う側を
     * 解析し直さず、古い値で条件を絞る（docs/cache-unification-qa.md の Q49 が値の側で避けたのと同じ取り違え）。
     * UTF-16 の符号化器も同じく置き換える（U+FFFD）ので使えない。{@link #bytesOf} は正しい UTF-16 の文字列には
     * UTF-8 と同じバイト列を返し、対になっていないサロゲートだけを、その符号単位を UTF-8 と同じ規則で 3 バイトに書く
     * （WTF-8 と呼ばれる形）。どの 2 つの文字列も同じバイト列にならない
     */
    public static String ofText(String text) {
        return hex(digest().digest(bytesOf(text)));
    }

    /**
     * 文字列を、情報を落とさずにバイト列にする。正しい UTF-16 なら UTF-8 と同じ。対になっていないサロゲートは、
     * その符号単位（U+D800〜U+DFFF）を UTF-8 の 3 バイトの形で書く。{@link String#codePoints} は対になった
     * サロゲートを 1 つの符号位置にまとめ、対になっていないものはそのまま返すので、どちらも取り違えない
     */
    static byte[] bytesOf(String text) {
        if (text.codePoints().noneMatch(FileHash::isSurrogate)) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3);
        text.codePoints().forEach(c -> {
            if (c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xC0 | (c >> 6));
                out.write(0x80 | (c & 0x3F));
            } else if (c < 0x10000) {
                out.write(0xE0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xF0 | (c >> 18));
                out.write(0x80 | ((c >> 12) & 0x3F));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        });
        return out.toByteArray();
    }

    /** 対になっていないサロゲートの符号単位か（{@link String#codePoints} が対のものは 1 つにまとめて返すので、残るのは対でないものだけ） */
    private static boolean isSurrogate(int c) {
        return c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE;
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
