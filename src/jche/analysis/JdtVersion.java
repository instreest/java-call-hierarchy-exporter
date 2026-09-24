// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 解析に使っている JDT の版。
 *
 * <p>キャッシュの鍵（ヘッダ行の {@code jdt=}）と、サーバーモードの {@code HELLO} の応答に使う。
 * 解析の結果（バインディング・JLS の解釈・ガードの条件式のテキスト）は JDT の版で変わりうるので、
 * 版が変わったらキャッシュを丸ごと捨てる（{@link jche.cache.CacheFormat#headerFor}）。
 *
 * <h2>2 つの jar を見る</h2>
 * JDT は 2 つの jar に分かれている。AST と {@code JavaCore} は {@code org.eclipse.jdt.core}、
 * 型解決（バインディング）をする本体のコンパイラは {@code ecj}（Bundle-SymbolicName は
 * {@code org.eclipse.jdt.core.compiler.batch}）にある。版の付け方も別々なので
 * （同じ 3.46.0 でも日付の修飾子が違う）、両方の Bundle-Version を {@code +} でつないで持つ。
 * 古い JDT のように同じ jar に入っていれば 1 つだけになる。
 *
 * <h2>分からなければ {@link #UNKNOWN}</h2>
 * jar の MANIFEST が読めない（クラスフォルダから動かしている等）ときは {@code ?} を返す。
 * キャッシュの側は {@code ?} を含む鍵を「一致しない」とみなすので、版が分からないまま
 * 古いキャッシュを再利用することはない（安全側。{@link jche.cache.CacheReader#headerMatches}）。
 */
public final class JdtVersion {

    /** 版が分からないことを表す値 */
    public static final String UNKNOWN = "?";

    private static final String CURRENT = compute();

    private JdtVersion() {
    }

    /** この JVM で使っている JDT の版（{@code core版+ecj版}）。分からなければ {@link #UNKNOWN} */
    public static String current() {
        return CURRENT;
    }

    private static String compute() {
        Path core = jarOf(org.eclipse.jdt.core.JavaCore.class);
        Path compiler = null;
        try {
            Class<?> c = Class.forName("org.eclipse.jdt.internal.compiler.Compiler", false,
                    org.eclipse.jdt.core.JavaCore.class.getClassLoader());
            compiler = jarOf(c);
        } catch (ClassNotFoundException | LinkageError e) {
            return UNKNOWN;
        }
        String coreVersion = bundleVersionOf(core);
        if (compiler == null || compiler.equals(core)) {
            return coreVersion;
        }
        String compilerVersion = bundleVersionOf(compiler);
        if (UNKNOWN.equals(coreVersion) || UNKNOWN.equals(compilerVersion)) {
            return UNKNOWN;
        }
        return coreVersion + "+" + compilerVersion;
    }

    /** クラスを読み込んだ jar。jar でなければ（クラスフォルダ等）null */
    private static Path jarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** jar の MANIFEST の Bundle-Version。読めなければ {@link #UNKNOWN} */
    private static String bundleVersionOf(Path jar) {
        if (jar == null) {
            return UNKNOWN;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            String version = (manifest == null) ? null
                    : manifest.getMainAttributes().getValue("Bundle-Version");
            return (version == null || version.isBlank()) ? UNKNOWN : version.trim();
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
