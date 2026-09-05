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
package jche.external;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import jche.cache.MethodRef;
import jche.config.Config;
import jche.graph.CallGraph;
import jche.graph.MethodTable;
import jche.report.CallHierarchyCsvWriter;
import jche.util.Log;

/**
 * 他チームのjarを走査し、自分のメソッドがどこから参照されているかを出力する。
 *
 * 用途は改修時の影響調査。「このメソッドを直すと誰に影響するか」に答える。
 * classファイルの定数プールだけを読む（{@link ClassFileRefs}）ため、
 * 「どのjar・どのクラスが参照しているか」までが分かり、呼び出し元メソッドと行番号は分からない。
 */
public final class ExternalUsageScanner {

    /** 被参照スキャンの集計 */
    public static final class Stats {
        long jars;
        long classes;
        long selfClasses;
        public long hits;
        public long implicitCtors;
        public long unmatched;
        long usedMethods;

        @Override
        public String toString() {
            return "jar=" + jars + " クラス=" + classes
                    + " 被参照=" + hits + "件（自分のメソッド " + usedMethods + " 個）"
                    + " 暗黙コンストラクタ=" + implicitCtors
                    + " 未照合=" + unmatched
                    + " 自プロジェクトクラスを除外=" + selfClasses;
        }
    }

    private final CallGraph graph;
    private final MethodTable methods;
    private final CallHierarchyCsvWriter out;
    private final Stats stats = new Stats();
    /** 自分の型かどうかの判定に使う（H行から得た、ソース上に宣言のある型） */
    private final Set<String> ourTypes;
    /** 継承したメソッドの照合用: "name(params)" -> 宣言しているメソッドID */
    private final Map<String, List<Integer>> bySignature = new HashMap<>();
    /** メソッドごとの被参照回数（「自分のメソッド N 個」の集計用） */
    private final int[] refCount;

    private ExternalUsageScanner(CallGraph graph, CallHierarchyCsvWriter out) {
        this.graph = graph;
        this.methods = graph.methods();
        this.out = out;
        this.ourTypes = graph.hierarchy().typeNames();
        for (int id = 0; id < methods.size(); id++) {
            if (methods.hasSource(id)) {
                bySignature.computeIfAbsent(methods.signature(id), k -> new ArrayList<>()).add(id);
            }
        }
        this.refCount = new int[methods.size()];
    }

    public static Stats scan(CallGraph graph, Config config, CallHierarchyCsvWriter out)
            throws IOException {
        List<Path> jars = collectJars(config.externalLibraryFolders);
        Log.info("外部jar: " + jars.size() + " 件");
        ExternalUsageScanner scanner = new ExternalUsageScanner(graph, out);
        for (Path jar : jars) {
            scanner.scanJar(jar);
        }
        return scanner.stats;
    }

    private void scanJar(Path jarPath) throws IOException {
        String jarName = jarPath.getFileName().toString();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassFileRefs refs;
                try (InputStream is = jar.getInputStream(entry)) {
                    refs = ClassFileRefs.parse(is);
                } catch (Exception ex) {
                    Log.warn("class解析に失敗（スキップ）: "
                            + jarName + "!" + entry.getName() + " (" + ex.getMessage() + ")");
                    continue;
                }
                // 自プロジェクトのクラスが混ざったjar（自分のビルド成果物が
                // 同じフォルダにある等）は「他リポジトリからの被参照」ではない。
                // 自分自身からの呼び出しを被参照として出さないよう読み飛ばす
                if (isOurType(refs.thisClass)) {
                    stats.selfClasses++;
                    continue;
                }
                stats.classes++;
                scanClass(refs, jarName);
            }
        }
        stats.jars++;
    }

    /** 1クラスが参照しているメソッドのうち、自分の型のものを行にする */
    private void scanClass(ClassFileRefs refs, String jarName) throws IOException {
        for (ClassFileRefs.MethodEntry r : refs.methodRefs) {
            String owner = r.ownerFqn();
            if (!isOurType(owner)) {
                continue;   // JDKや第三者ライブラリへの参照は対象外
            }
            String sig = r.name() + "(" + r.paramSig() + ")";
            int id = resolveRef(owner, sig);
            if (id >= 0) {
                String kind = methods.typeFqn(id).equals(normalize(owner)) ? "EXACT" : "INHERITED";
                out.writeExternalUsageRow(refs.thisClass, methods.displayLabel(id),
                        methods.shortLabel(id), jarName, kind);
                if (refCount[id]++ == 0) {
                    stats.usedMethods++;
                }
                stats.hits++;
            } else if (MethodRef.CONSTRUCTOR.equals(r.name()) && r.paramSig().isEmpty()) {
                // 引数なしコンストラクタへの参照だが、ソース上に一致する宣言が無い。
                // 暗黙のデフォルトコンストラクタは解析時に D 行として合成されるので EXACT で
                // 照合される。ここに来るのは「相手jarのビルド時には引数なしで生成できたが、
                // 今のソースにはそのコンストラクタが無い」形で、版違いの可能性が高い。
                // 「誰がこのクラスを生成しているか」は影響調査で有用なので、行として残し注記で区別する。
                // 引数付きの <init> が一致しないものは、内部クラス（外側インスタンスが引数に付く）や
                // 版違いであり、生成箇所として表記できないので未照合に数える
                String typeFqn = normalize(owner);
                String simple = simpleOf(typeFqn);
                out.writeExternalUsageRow(refs.thisClass,
                        typeFqn + "." + simple + "()", simple + "." + simple,
                        jarName, "IMPLICIT_CTOR");
                stats.implicitCtors++;
            } else {
                // 自分の型への参照なのに一致するメソッドが無い。
                // 相手が古い版のjarに対してビルドされている可能性がある。
                // 「使われていない」と即断しないよう件数だけ残す
                stats.unmatched++;
            }
        }
    }

    private static String simpleOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return (i >= 0) ? fqn.substring(i + 1) : fqn;
    }

    /** 内部クラスは bytecode が Outer$Inner、JDT側が Outer.Inner なので両方で照合する */
    private static String normalize(String owner) {
        return owner.replace('$', '.');
    }

    private boolean isOurType(String owner) {
        return ourTypes.contains(owner) || ourTypes.contains(normalize(owner));
    }

    /**
     * 参照を自分のメソッドIDに解決する。
     *
     * シグネチャは classファイルのディスクリプタから作るため、引数の内部クラスが
     * Outer$Inner の形で入る。JDT側は Outer.Inner なので、そのままでは
     * 「内部クラスを引数に取るオーバーロード」だけが照合できず、未照合に落ちる。
     * まず生の形で引き、外れたら $ を . に直した形でもう一度引く
     * （クラス名に $ を含む型を誤って読み替えないよう、生の形を先に試す）。
     */
    private int resolveRef(String owner, String sig) {
        int id = lookupRef(owner, sig);
        if (id >= 0) {
            return id;
        }
        String normSig = normalize(sig);
        return normSig.equals(sig) ? -1 : lookupRef(owner, normSig);
    }

    /**
     * 完全一致で見つからない場合、継承したメソッドの呼び出し
     * （呼び出し側は子クラスを owner として記録する）を考慮して親を探す。
     */
    private int lookupRef(String owner, String sig) {
        int id = methods.idOf(owner + "#" + sig);
        if (id >= 0 && methods.hasSource(id)) {
            return id;
        }
        String norm = normalize(owner);
        id = methods.idOf(norm + "#" + sig);
        if (id >= 0 && methods.hasSource(id)) {
            return id;
        }
        List<Integer> candidates = bySignature.get(sig);
        if (candidates == null) {
            return -1;
        }
        for (int c : candidates) {
            List<String> subtypes = graph.hierarchy().transitiveSubtypes(methods.typeFqn(c));
            if (subtypes.contains(norm) || subtypes.contains(owner)) {
                return c;
            }
        }
        return -1;
    }

    /** 指定がファイルならそのjar、ディレクトリなら配下の *.jar を全部（サブフォルダも見る） */
    private static List<Path> collectJars(List<Path> roots) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        for (Path r : roots) {
            if (Files.isRegularFile(r) && r.toString().endsWith(".jar")) {
                out.add(r);
            } else if (Files.isDirectory(r)) {
                try (Stream<Path> walk = Files.walk(r)) {
                    walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                            .forEach(out::add);
                }
            } else {
                Log.warn("external.library.folders の指定が見つかりません: " + r);
            }
        }
        return new ArrayList<>(out);
    }
}
