/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import jche.cache.FileAnalysis;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.extension.CallSiteHintCollector;
import jche.util.Log;

/**
 * ソースをASTパースし、宣言・呼び出し・フィールド・出所などの事実を抽出する。
 *
 * ワークスペースを使わない「スタンドアロンモード」で動かすため、
 * ASTParser.setEnvironment() にソースパスとクラスパスを明示的に渡す。
 *
 * <h2>一括パース</h2>
 * ファイルごとに ASTParser を作って createAST すると、JDT が呼び出しのたびにクラスパスと
 * ソースパスの名前環境を組み直し、参照先のソースも読み直すため、1ファイルあたりの時間が
 * プロジェクト規模に比例して増える（800 ファイルの実測で、一括に比べて約 14 倍）。
 * そのため {@link #BATCH_SIZE} 件ずつ {@code createASTs} でまとめてパースし、
 * 1ファイル分の AST が出来るたびに {@link Sink} へ渡す。AST は渡した直後に捨てられるので、
 * ヒープに載るのは1バッチ分に留まる。
 */
public final class CallEdgeExtractor {

    /** 一括でパースするファイル数。進捗ログの間隔もこれに揃える */
    public static final int BATCH_SIZE = 100;

    /**
     * 解析対象のソースファイル1件。
     *
     * @param path         実体のパス
     * @param relativePath project.root からの相対パス（キャッシュのキー・出力の file 列）
     * @param mtime        更新時刻（差分更新の判定用）
     * @param size         サイズ（同上）
     */
    public record SourceFile(Path path, String relativePath, long mtime, long size) {
    }

    /** 解析結果の受け手。1ファイル分ずつ渡すので、受け手は書き出したら捨てられる */
    public interface Sink {
        /** 解析できた1ファイル。IOException はキャッシュへの書き込み失敗で、解析全体を止める */
        void accept(SourceFile file, FileAnalysis analysis) throws IOException;

        /** 解析に失敗した1ファイル（読み飛ばして続行する） */
        void failed(SourceFile file, Exception error);
    }

    private final ProjectLayout layout;
    private final Charset encoding;
    private final String encodingName;
    private final Map<String, String> compilerOptions;
    private final String[] classpath;
    private final String[] sourcepath;
    private final String[] sourcepathEncodings;
    private final List<CallSiteHintCollector> collectors;

    public CallEdgeExtractor(ProjectLayout layout, Config config) {
        this.collectors = Plugins.load(config.hintCollectorClasses, CallSiteHintCollector.class);
        this.layout = layout;
        this.encodingName = config.sourceEncoding;
        this.encoding = Charset.forName(config.sourceEncoding);
        // 準拠レベル（source.level）は Config が解決済み。
        // 既定のまま使うと generics・diamond演算子・ラムダ式・enum等が
        // 軒並み構文/型解決に失敗するので、必ずこちらを使うこと
        this.compilerOptions = config.compilerOptions;
        this.classpath = layout.classpathArray();
        this.sourcepath = layout.sourcePathArray();
        this.sourcepathEncodings = new String[sourcepath.length];
        Arrays.fill(this.sourcepathEncodings, config.sourceEncoding);
    }

    /**
     * 複数のファイルをまとめてパースし、1ファイル分ずつ sink へ渡す。
     *
     * JDT が受け付けなかったファイルや、一括パース自体が失敗したときの残りは、
     * 1ファイルずつ {@link #analyze} で解析する。1ファイルの失敗で他を巻き込まないため。
     */
    public void analyzeBatch(List<SourceFile> files, Sink sink) throws IOException {
        Map<String, SourceFile> pending = new LinkedHashMap<>();
        for (SourceFile file : files) {
            pending.put(file.path().toString(), file);
        }
        String[] paths = pending.keySet().toArray(new String[0]);
        String[] fileEncodings = new String[paths.length];
        Arrays.fill(fileEncodings, encodingName);

        try {
            newParser().createASTs(paths, fileEncodings, new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                    SourceFile file = pending.remove(sourceFilePath);
                    if (file == null) {
                        return;
                    }
                    FileAnalysis facts;
                    try {
                        facts = collectFacts(file, cu);
                    } catch (RuntimeException e) {
                        sink.failed(file, e);
                        return;
                    }
                    try {
                        sink.accept(file, facts);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);   // 下の catch で IOException に戻す
                    }
                }
            }, null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            Log.warn("一括解析に失敗したため、残り " + pending.size() + " 件は1ファイルずつ解析します (" + e + ")");
        }

        if (!pending.isEmpty()) {
            for (SourceFile file : new ArrayList<>(pending.values())) {
                FileAnalysis facts;
                try {
                    facts = analyze(file);
                } catch (IOException | RuntimeException e) {
                    sink.failed(file, e);
                    continue;
                }
                sink.accept(file, facts);
            }
        }
    }

    /** 1ファイルだけをパースする（一括パースの補完用） */
    public FileAnalysis analyze(SourceFile file) throws IOException {
        char[] source = new String(Files.readAllBytes(file.path()), encoding).toCharArray();
        ASTParser parser = newParser();
        parser.setUnitName(layout.unitNameOf(file.path()));  // バインディング解決に必須
        parser.setSource(source);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return collectFacts(file, cu);
    }

    /** ワークスペース非依存で型解決するための設定を済ませたパーサ */
    private ASTParser newParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(compilerOptions);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setEnvironment(classpath, sourcepath, sourcepathEncodings, true);
        return parser;
    }

    private FileAnalysis collectFacts(SourceFile file, CompilationUnit cu) {
        FileAnalysis result = new FileAnalysis(file.relativePath(), file.mtime(), file.size());
        // 型が見つからない等のエラーは「解決が不完全」の印。依存 jar が増えたら解析し直せるよう数を残す
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError()) {
                result.errors++;
            }
        }
        collectImports(cu, result);
        cu.accept(new FactVisitor(cu, result, collectors));
        return result;
    }

    /**
     * import 文の型も依存に数える。解決に失敗した import（jar不足）が後から
     * 解決できるようになったときに、このファイルを解析し直せるようにするため
     */
    private static void collectImports(CompilationUnit cu, FileAnalysis result) {
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            if (imp.isOnDemand()) {
                result.imports.add(name + ".*");
                continue;
            }
            result.imports.add(name);
            if (imp.isStatic()) {
                // import static a.B.c; の a.B（メンバではなく型）も依存
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    result.imports.add(name.substring(0, dot));
                }
            }
        }
    }
}
