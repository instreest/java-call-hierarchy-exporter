// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;

/**
 * zip（jar・jmod）の目次（セントラルディレクトリ）を、<b>ファイルのバイトから直接</b>読む
 * （{@link LibraryDiff} の jar の指紋に使う）。
 *
 * <p>{@link java.util.zip.ZipFile} を使わないのは、JDK が同じ jar の目次を「パス（inode）と更新時刻」を鍵にして
 * プロセスの中で共有するため。JDT は開いた jar を閉じない（ガベージコレクションまで開いたまま）ので、解析サーバーのように
 * 同じプロセスで解析を繰り返すと、同じ更新時刻のまま上書きされた jar の<b>前の目次</b>を {@code ZipFile} が返し、
 * 指紋が変わらず「変わっていない」と読んでいた。ここで読むのはいつもディスクの今の中身である。
 *
 * <p>読み方は {@code ZipFile} に合わせる。
 * <ul>
 *   <li>目次の終わり（EOCD）を後ろから探し、その直前に zip64 の案内（locator）があれば zip64 の値を使う</li>
 *   <li>目次の位置は「終わりの位置 − 目次の長さ」で求める（先頭に別のデータがある jmod でもずれない）</li>
 *   <li>名前は UTF-8 で読む（{@code new ZipFile(file)} の既定）。読めない名前は {@link ZipException}</li>
 *   <li>サイズが 0xFFFFFFFF なら zip64 の拡張フィールドの値を使う</li>
 * </ul>
 * 同じ名前の項目が 2 つあれば、目次の順のまま両方を返す（JDK と JDT は後ろのものを使う）。
 */
final class ZipDirectory {

    /** 目次の 1 項目（{@link java.util.zip.ZipEntry} の名前・サイズ・CRC と同じ値） */
    record Entry(String name, long size, long crc) {
    }

    private static final int END_SIG = 0x06054b50;
    private static final int END_LEN = 22;
    private static final int ZIP64_LOC_SIG = 0x07064b50;
    private static final int ZIP64_LOC_LEN = 20;
    private static final int ZIP64_END_SIG = 0x06064b50;
    private static final int ZIP64_END_LEN = 56;
    private static final int CEN_SIG = 0x02014b50;
    private static final int CEN_LEN = 46;
    private static final long MAGIC32 = 0xFFFFFFFFL;
    private static final int ZIP64_EXTRA_ID = 0x0001;

    private ZipDirectory() {
    }

    /** 目次の項目を目次の順に返す。zip として読めなければ {@link IOException} */
    static List<Entry> read(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long length = ch.size();
            if (length < END_LEN) {
                throw new ZipException("zip END header not found");
            }
            int tailLength = (int) Math.min(length, END_LEN + 0xFFFF);
            long tailStart = length - tailLength;
            ByteBuffer tail = readAt(ch, tailStart, tailLength);
            int end = -1;
            for (int i = tailLength - END_LEN; i >= 0; i--) {
                if (tail.getInt(i) != END_SIG) {
                    continue;
                }
                // 注釈の長さがファイルの終わりに合わない（後ろに余計なバイトがある）ときは、目次と最初の項目の署名が
                // 合うときだけ EOCD とみなす（ZipFile と同じ）
                if (i + END_LEN + u16(tail, i + 20) == tailLength
                        || signaturesMatch(ch, tailStart + i, u32(tail, i + 12), u32(tail, i + 16))) {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                throw new ZipException("zip END header not found");
            }
            long endPos = tailStart + end;
            long cenLength = u32(tail, end + 12);
            ByteBuffer end64 = zip64EndOf(ch, endPos);
            if (end64 != null && sameAsZip64(tail, end, end64)) {
                cenLength = end64.getLong(40);
                endPos = end64.getLong(ZIP64_END_LEN);   // zip64EndOf が後ろに足した位置
            }
            long cenPos = endPos - cenLength;
            if (cenLength < 0 || cenPos < 0 || cenLength > Integer.MAX_VALUE) {
                throw new ZipException("invalid END header (bad central directory offset)");
            }
            return entriesOf(readAt(ch, cenPos, (int) cenLength));
        }
    }

    /** 目次の位置（終わりの位置 − 長さ）と最初の項目の位置（目次の位置 − オフセット）に、それぞれの署名があるか */
    private static boolean signaturesMatch(FileChannel ch, long endPos, long cenLength, long cenOffset)
            throws IOException {
        long cenPos = endPos - cenLength;
        long locPos = cenPos - cenOffset;
        if (cenPos < 0 || locPos < 0 || cenPos + 4 > ch.size()) {
            return false;
        }
        return readAt(ch, cenPos, 4).getInt(0) == CEN_SIG && readAt(ch, locPos, 4).getInt(0) == 0x04034b50;
    }

    /**
     * zip64 の目次の終わり（56 バイト）の後ろに、その位置を 8 バイト足して返す。zip64 でなければ null
     * （{@code ZipFile} と同じく、EOCD の直前の案内と、案内が指す先の署名が揃うときだけ使う）
     */
    private static ByteBuffer zip64EndOf(FileChannel ch, long endPos) throws IOException {
        if (endPos < ZIP64_LOC_LEN) {
            return null;
        }
        ByteBuffer loc = readAt(ch, endPos - ZIP64_LOC_LEN, ZIP64_LOC_LEN);
        if (loc.getInt(0) != ZIP64_LOC_SIG) {
            return null;
        }
        long pos = loc.getLong(8);
        if (pos < 0 || pos + ZIP64_END_LEN > ch.size()) {
            return null;
        }
        ByteBuffer end64 = readAt(ch, pos, ZIP64_END_LEN);
        if (end64.getInt(0) != ZIP64_END_SIG) {
            return null;
        }
        ByteBuffer withPos = ByteBuffer.allocate(ZIP64_END_LEN + 8).order(ByteOrder.LITTLE_ENDIAN);
        withPos.put(end64).putLong(pos).flip();
        return withPos;
    }

    /** zip64 の値が EOCD の値と食い違わないか（EOCD の値が 0xFFFF… の印なら、何でもよい）。ZipFile と同じ確かめ */
    private static boolean sameAsZip64(ByteBuffer tail, int end, ByteBuffer end64) {
        long total = u16(tail, end + 10);
        long cenLength = u32(tail, end + 12);
        long cenOffset = u32(tail, end + 16);
        return (end64.getLong(40) == cenLength || cenLength == MAGIC32)
                && (end64.getLong(48) == cenOffset || cenOffset == MAGIC32)
                && (end64.getLong(32) == total || total == 0xFFFF);
    }

    private static List<Entry> entriesOf(ByteBuffer cen) throws ZipException {
        List<Entry> entries = new ArrayList<>();
        int pos = 0;
        int limit = cen.limit();
        while (pos + CEN_LEN <= limit) {
            if (cen.getInt(pos) != CEN_SIG) {
                throw new ZipException("invalid CEN header (bad signature)");
            }
            long crc = u32(cen, pos + 16);
            long size = u32(cen, pos + 24);
            int nameLength = u16(cen, pos + 28);
            int extraLength = u16(cen, pos + 30);
            int commentLength = u16(cen, pos + 32);
            int nameStart = pos + CEN_LEN;
            int next = nameStart + nameLength + extraLength + commentLength;
            if (next > limit) {
                throw new ZipException("invalid CEN header (bad header size)");
            }
            String name = utf8(cen, nameStart, nameLength);
            if (size == MAGIC32) {
                size = zip64SizeOf(cen, nameStart + nameLength, extraLength, size);
            }
            entries.add(new Entry(name, size, crc));
            pos = next;
        }
        return entries;
    }

    /** 拡張フィールドの zip64 の項目から元のサイズを取る。無ければ {@code size} のまま */
    private static long zip64SizeOf(ByteBuffer cen, int start, int length, long size) {
        int pos = start;
        int end = start + length;
        while (pos + 4 <= end) {
            int id = u16(cen, pos);
            int dataLength = u16(cen, pos + 2);
            int data = pos + 4;
            if (data + dataLength > end) {
                break;
            }
            if (id == ZIP64_EXTRA_ID && dataLength >= 8) {
                return cen.getLong(data);
            }
            pos = data + dataLength;
        }
        return size;
    }

    private static String utf8(ByteBuffer buf, int start, int length) throws ZipException {
        ByteBuffer slice = buf.duplicate();
        slice.position(start).limit(start + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(slice).toString();
        } catch (CharacterCodingException e) {
            throw new ZipException("invalid CEN header (bad entry name)");
        }
    }

    private static ByteBuffer readAt(FileChannel ch, long pos, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long at = pos;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, at);
            if (n < 0) {
                throw new EOFException("unexpected end of zip file");
            }
            at += n;
        }
        buf.flip();
        return buf;
    }

    private static int u16(ByteBuffer buf, int pos) {
        return Short.toUnsignedInt(buf.getShort(pos));
    }

    private static long u32(ByteBuffer buf, int pos) {
        return Integer.toUnsignedLong(buf.getInt(pos));
    }
}
