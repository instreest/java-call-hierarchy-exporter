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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import jche.cache.FileAnalysis;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.extension.CallSiteHintCollector;

/**
 * ソース1ファイルをASTパースし、宣言・呼び出し・フィールド・出所などの事実を抽出する。
 *
 * ワークスペースを使わない「スタンドアロンモード」で動かすため、
 * ASTParser.setEnvironment() にソースパスとクラスパスを明示的に渡す。
 * ASTは1ファイルごとに生成して捨てるため、ヒープには残らない。
 */
public final class CallEdgeExtractor {

    private final ProjectLayout layout;
    private final Charset encoding;
    private final Map<String, String> compilerOptions;
    private final String[] classpath;
    private final String[] sourcepath;
    private final String[] encodings;
    private final List<CallSiteHintCollector> collectors;

    public CallEdgeExtractor(ProjectLayout layout, Config config) {
        this.collectors = Plugins.load(config.hintCollectorClasses, CallSiteHintCollector.class);
        this.layout = layout;
        this.encoding = Charset.forName(config.sourceEncoding);
        // 準拠レベル（source.level）は Config が解決済み。
        // 既定のまま使うと generics・diamond演算子・ラムダ式・enum等が
        // 軒並み構文/型解決に失敗するので、必ずこちらを使うこと
        this.compilerOptions = config.compilerOptions;
        this.classpath = layout.classpathArray();
        this.sourcepath = layout.sourcePathArray();
        this.encodings = new String[sourcepath.length];
        Arrays.fill(this.encodings, config.sourceEncoding);
    }

    public FileAnalysis analyze(Path javaFile, String relativePath, long mtime, long size)
            throws IOException {
        FileAnalysis result = new FileAnalysis(relativePath, mtime, size);
        char[] source = new String(Files.readAllBytes(javaFile), encoding).toCharArray();

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(compilerOptions);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        // ワークスペース非依存で型解決するための環境設定
        parser.setEnvironment(classpath, sourcepath, encodings, true);
        parser.setUnitName(layout.unitNameOf(javaFile));  // バインディング解決に必須
        parser.setSource(source);

        IProgressMonitor noMonitor = null;
        CompilationUnit cu = (CompilationUnit) parser.createAST(noMonitor);
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
