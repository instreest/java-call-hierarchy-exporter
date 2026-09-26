// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/**
 * フォルダの中のファイルを、JDT と同じく<b>シンボリックリンクをたどって</b>歩く。
 *
 * <p>JDT はソースパスとクラスパスのフォルダを {@code java.io.File} で読むので、リンクになったフォルダ
 * （ソースフォルダそのもの・中のパッケージのフォルダ・{@code target/classes}）の中の型も見つける。
 * 一覧や指紋を作る側が {@link Files#walk} の既定（リンクをたどらない）で歩くと、JDT が読んでいるファイルが
 * 一覧からも指紋からも抜け、変更を検知できない（docs/cache-unification-qa.md）。
 *
 * <p>リンクが祖先のフォルダを指して輪になっているところは、その先へ入らない（中身はすでに歩いた祖先と同じ）。
 * 行き先の無いリンクは、ふつうのファイルではないので渡さない。それ以外の読めないところは {@link IOException} にする
 * （黙って飛ばすと、一覧や指紋から抜けたことに気づけない）。
 *
 * <p>渡す順はファイルシステムに依存する。並びを決めたいときは呼び出し側で並べ替える。
 */
public final class FileTree {

    private FileTree() {
    }

    /** 1 ファイルごとの処理。属性はリンクをたどった先のもの */
    @FunctionalInterface
    public interface FileAction {
        void accept(Path file, BasicFileAttributes attrs) throws IOException;
    }

    /**
     * {@code root} の下のふつうのファイルを 1 つずつ {@code action} に渡す。渡すパスは {@code root} から
     * たどった綴り（リンクの行き先に置き換えない）なので、{@code root.relativize} がそのまま使える。
     */
    public static void forEachFile(Path root, FileAction action) throws IOException {
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isRegularFile()) {
                            action.accept(file, attrs);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        if (exc instanceof FileSystemLoopException) {
                            return FileVisitResult.CONTINUE;   // 祖先へ戻るリンク。先は歩いた祖先と同じ
                        }
                        throw exc;
                    }
                });
    }
}
