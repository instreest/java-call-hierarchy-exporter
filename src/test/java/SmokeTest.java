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

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SmokeTest {
    static int ng = 0;
    static void check(String label, boolean ok, String detail) {
        System.out.println((ok?"  OK   ":"  NG   ")+label+(ok?"":"  -> "+detail));
        if(!ok) ng++;
    }
    static final String T = "\t";
    static String F(String p,long m,long s){return String.join(T,"F",p,""+m,""+s);}
    static String D(String pk,String ty,String me,String pa,int ln){return String.join(T,"D",pk,ty,me,pa,""+ln,"1");}
    static String C(String[] a,String[] b,int ln){return String.join(T,"C",a[0],a[1],a[2],a[3],b[0],b[1],b[2],b[3],""+ln,"S","");}
    static String[] M(String pk,String ty,String me){return new String[]{pk,ty,me,""};}

    public static void main(String[] x) throws Exception {
        Path tmp = Files.createTempDirectory("cht2");
        Path cfgDir = tmp.resolve("conf"); Files.createDirectories(cfgDir);
        Path srcDir = tmp.resolve("proj/src/jp/co/xxx/action"); Files.createDirectories(srcDir);
        Files.writeString(tmp.resolve("proj/.classpath"),
            "<classpath><classpathentry kind=\"src\" path=\"src\"/></classpath>", StandardCharsets.UTF_8);
        Path srcFile = srcDir.resolve("UserAction.java");
        Files.writeString(srcFile, "class UserAction{}", StandardCharsets.UTF_8);

        Path cfg = cfgDir.resolve("config.properties");
        Files.writeString(cfg, String.join("\n",
            "project.root=../proj",
            "entry.packages=jp.co.xxx.action.*, jp.co.xxx.batch.**",
            "exclude.packages=java.**, jp.co.xxx.common.util.**",
            "exclude.mode=PRUNE","max.depth=6","max.children.per.node=50",
            "output.csv=./out/call-hierarchy.csv","unresolved.csv=./out/unresolved.csv",
            "cache.file=./cache/c.tsv","output.encoding=MS932"), StandardCharsets.UTF_8);

        System.out.println("[1] 相対パス解決");
        CallHierarchyExporter.Config c = new CallHierarchyExporter.Config(cfg);
        check("project.root", c.projectRoot.equals(tmp.resolve("proj").normalize()), ""+c.projectRoot);
        check("output.csv",  c.outputCsv.equals(cfgDir.resolve("out/call-hierarchy.csv").normalize()), ""+c.outputCsv);

        System.out.println("[2] .classpath 読み取り");
        CallHierarchyExporter.EclipseProjectLayout ly = new CallHierarchyExporter.EclipseProjectLayout(c);
        check("src検出", ly.sourceFolders.size()==1, ly.sourceFolders.toString());

        System.out.println("[3] パターンマッチ");
        check("* 直下", CallHierarchyExporter.PackagePattern.matchesAny(c.entryPatterns,"jp.co.xxx.action","jp.co.xxx.action.UserAction","execute"),"");
        check("* 内部クラス", CallHierarchyExporter.PackagePattern.matchesAny(c.entryPatterns,"jp.co.xxx.action","jp.co.xxx.action.UserAction.Helper","calc"),"");
        check("* サブpkg非マッチ", !CallHierarchyExporter.PackagePattern.matchesAny(c.entryPatterns,"jp.co.xxx.action.sub","jp.co.xxx.action.sub.S","run"),"");
        check("** サブpkgマッチ", CallHierarchyExporter.PackagePattern.matchesAny(c.entryPatterns,"jp.co.xxx.batch.night","jp.co.xxx.batch.night.N","run"),"");

        System.out.println("[4] キャッシュのストリーミングマージ（再解析なし）");
        long mt = Files.getLastModifiedTime(srcFile).toMillis(), sz = Files.size(srcFile);
        Files.createDirectories(c.cacheFile.getParent());
        String[] act=M("jp.co.xxx.action","jp.co.xxx.action.UserAction","execute");
        String[] svc=M("jp.co.xxx.service","jp.co.xxx.service.UserService","findUser");
        String[] dao=M("jp.co.xxx.dao","jp.co.xxx.dao.UserDaoImpl","findUserById");
        String[] utl=M("jp.co.xxx.common.util","jp.co.xxx.common.util.StrUtil","trim");
        List<String> cache = new ArrayList<>(List.of("jche-cache-v1",
            F("src/jp/co/xxx/action/UserAction.java", mt, sz),
            D(act[0],act[1],act[2],"",45), D(svc[0],svc[1],svc[2],"",23),
            D(dao[0],dao[1],dao[2],"",67), D(utl[0],utl[1],utl[2],"",9),
            C(act,svc,50), C(act,utl,51), C(svc,dao,30),
            String.join(T,"U","99","jp.co.xxx.action.UserAction#execute()","doIt","型解決に失敗")));
        Files.write(c.cacheFile, cache, StandardCharsets.UTF_8);

        CallHierarchyExporter.CachePhaseResult r = new CallHierarchyExporter.CacheUpdater(ly, c).run();
        check("再利用=1", r.reused==1, "reused="+r.reused);
        check("新規解析=0", r.parsed==0, "parsed="+r.parsed);
        check("未解決も逐次出力", r.unresolvedCount==1, ""+r.unresolvedCount);
        check("unresolved.csv生成", Files.readAllLines(c.unresolvedCsv, java.nio.charset.Charset.forName("MS932")).size()==2, "");

        System.out.println("[5] CSRグラフ構築 + DFS逐次出力");
        CallHierarchyExporter.CallGraph g = CallHierarchyExporter.CallGraph.buildFrom(c.cacheFile);
        check("メソッド数=4", g.methodCount()==4, ""+g.methodCount());
        check("エッジ数=3", g.edgeCount()==3, ""+g.edgeCount());
        int[] es = g.selectEntryPoints(c);
        check("エントリ=1", es.length==1, ""+es.length);

        CallHierarchyExporter.CallHierarchyCsvWriter w = new CallHierarchyExporter.CallHierarchyCsvWriter(c.outputCsv,c.outputEncoding,c.outputBom,c.outputDelimiter);
        long rows = new CallHierarchyExporter.StreamingTreeWalker(g,c,w).walkAll(es);
        w.close();
        List<String> L = Files.readAllLines(c.outputCsv, java.nio.charset.Charset.forName("MS932"));
        L.forEach(s->System.out.println("      "+s));
        check("2行(起点行と除外1件を除く)", rows==2 && L.size()==3, "rows="+rows);
        check("除外が出力されない", L.stream().noneMatch(s->s.contains("StrUtil")), "");
        check("行末grep可", L.get(2).endsWith("UserDaoImpl.findUserById"), L.get(2));
        check("caller=呼出行50", L.get(1).contains("UserAction.java:50"), L.get(1));
        check("callee列がフィルタ可能な短縮表記", L.get(1).split(",")[1].equals("UserService.findUser"), L.get(1));

        System.out.println("[6] 循環検出");
        Path c2=c.cacheFile.getParent().resolve("cyc.tsv");
        String[] A=M("p","p.A","f"), B=M("p","p.B","g");
        Files.write(c2, List.of("jche-cache-v1", F("A.java",1,1),
            D("p","p.A","f","",1), D("p","p.B","g","",1), C(A,B,2), C(B,A,3)), StandardCharsets.UTF_8);
        CallHierarchyExporter.CallGraph g2=CallHierarchyExporter.CallGraph.buildFrom(c2);
        Path o2=c.outputCsv.getParent().resolve("cyc.csv");
        CallHierarchyExporter.CallHierarchyCsvWriter w2=new CallHierarchyExporter.CallHierarchyCsvWriter(o2,c.outputEncoding,c.outputBom,c.outputDelimiter);
        long r2=new CallHierarchyExporter.StreamingTreeWalker(g2,c,w2).walkAll(new int[]{0}); w2.close();
        check("無限ループしない", r2<20, ""+r2);
        check("循環注記あり", Files.readAllLines(o2, java.nio.charset.Charset.forName("MS932")).stream().anyMatch(s->s.contains("[CYCLE]")), "");

        System.out.println("[7] 逐次出力の耐性（爆発する木＋行数上限）");
        Path c3=c.cacheFile.getParent().resolve("big.tsv");
        List<String> big=new ArrayList<>(List.of("jche-cache-v1", F("B.java",1,1)));
        int W=8, DPT=7;
        for(int d=0;d<=DPT;d++) for(int i=0;i<Math.min(Math.pow(W,d),200);i++)
            big.add(D("q","q.C"+d+"_"+i,"m","",1));
        for(int d=0;d<DPT;d++){
            int par=(int)Math.min(Math.pow(W,d),200);
            for(int i=0;i<par;i++) for(int k=0;k<W;k++){
                int ci=(i*W+k)%((int)Math.min(Math.pow(W,d+1),200));
                big.add(C(M("q","q.C"+d+"_"+i,"m"), M("q","q.C"+(d+1)+"_"+ci,"m"),1));
            }
        }
        Files.write(c3,big,StandardCharsets.UTF_8);
        Path cfg3=cfgDir.resolve("c3.properties");
        Files.writeString(cfg3,String.join("\n","project.root=../proj","entry.packages=q.*",
            "max.depth=6","max.children.per.node=50","max.rows.per.entry=5000",
            "output.csv=./out/big.csv","cache.file=./cache/big.tsv"),StandardCharsets.UTF_8);
        CallHierarchyExporter.Config c3c=new CallHierarchyExporter.Config(cfg3);
        CallHierarchyExporter.CallGraph g3=CallHierarchyExporter.CallGraph.buildFrom(c3);
        long before=used();
        CallHierarchyExporter.CallHierarchyCsvWriter w3=new CallHierarchyExporter.CallHierarchyCsvWriter(c3c.outputCsv,c3c.outputEncoding,c3c.outputBom,c3c.outputDelimiter);
        long r3=new CallHierarchyExporter.StreamingTreeWalker(g3,c3c,w3).walkAll(new int[]{0}); w3.close();
        long after=used();
        check("行数上限で打ち切られる", r3<=5000+10, ""+r3);
        check("ヒープ増加が小さい(<30MB)", (after-before)<30*1024*1024, ((after-before)/1048576)+"MB");
        System.out.println("      出力行数="+r3+" ヒープ増分="+((after-before)/1048576)+"MB");

        System.out.println("\n=== "+(ng==0?"全項目OK":ng+"件 NG")+" ===");
        if(ng>0) System.exit(1);
    }
    static long used(){ System.gc(); Runtime r=Runtime.getRuntime(); return r.totalMemory()-r.freeMemory(); }
}
