// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * JDK が同じプロセスの中で jar の前の目次を見せ続け、それを解き放てないとき、{@link LibraryDiff} が<b>次の解析でも</b>
 * 気づくことの検査（test/incremental/run.sh の「解き放てない前の目次」）。
 *
 * <pre>
 *   java jche.analysis.StaleSharedViewCheck &lt;jar&gt; &lt;同じ jar の別の中身&gt;
 * </pre>
 * JDT が開いたまま閉じない jar の {@link ZipFile} を、ここでは手で開いたまま持って作る。jar を同じ inode・同じ更新時刻の
 * まま別の中身に書き換え、持ったまま 2 回走査する。どちらも「前の目次を見せている」（{@link LibraryDiff#staleInProcess}）
 * でなければならない。以前は 1 回目で覚える指紋を今の中身のものに置き換えていたので、2 回目は指紋が一致して確かめず、
 * 1 回目が内容ハッシュを空にしたファイルを前の目次で解析し直して、正しいものとして書いていた。
 * 閉じたあとの 3 回目は解き放てる（前の目次ではない）。終了コードは食い違いの数。
 */
public final class StaleSharedViewCheck {

    private StaleSharedViewCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]).toAbsolutePath();
        byte[] other = Files.readAllBytes(Paths.get(args[1]));
        String[] classpath = {jar.toString()};
        int bad = 0;
        bad += expect("最初の走査", LibraryDiff.compute(classpath, List.of(), jar.getParent()), false);
        FileTime mtime = Files.getLastModifiedTime(jar);
        try (ZipFile holder = new ZipFile(jar.toFile())) {
            if (holder.size() == 0) {
                throw new IllegalStateException("空の jar です: " + jar);
            }
            try (FileChannel ch = FileChannel.open(jar, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(other);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
            Files.setLastModifiedTime(jar, mtime);
            bad += expect("開いたまま書き換えた直後の走査", LibraryDiff.compute(classpath, List.of(), jar.getParent()), true);
            bad += expect("開いたままの 2 回目の走査", LibraryDiff.compute(classpath, List.of(), jar.getParent()), true);
        }
        bad += expect("閉じたあとの走査", LibraryDiff.compute(classpath, List.of(), jar.getParent()), false);
        System.exit(bad);
    }

    private static int expect(String label, LibraryDiff diff, boolean stale) {
        if (diff.staleInProcess == stale) {
            System.out.println("OK   " + label + ": 前の目次を見せている=" + stale);
            return 0;
        }
        System.out.println("NG   " + label + ": 前の目次を見せている=" + diff.staleInProcess + "（期待は " + stale + "）");
        return 1;
    }
}
