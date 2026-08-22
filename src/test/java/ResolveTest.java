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

/** 型階層(H行)と解決パイプライン(段0/段1/CHA)の検証 */
public class ResolveTest {
    static int ng=0;
    static void check(String l,boolean ok,String d){System.out.println((ok?"  OK   ":"  NG   ")+l+(ok?"":"  -> "+d)); if(!ok)ng++;}
    static final String T="\t";
    static String H(String ty,char k,String... sup){return String.join(T,"H",ty,""+k,String.join(",",sup));}
    static String D(String ty,String m,int ln,boolean body){return String.join(T,"D",pkg(ty),ty,m,"",""+ln,body?"1":"0");}
    static String C(String ct,String cm,String et,String em,int ln,char bk){
        return String.join(T,"C",pkg(ct),ct,cm,"",pkg(et),et,em,"",""+ln,""+bk,"");}
    static String pkg(String fqn){int i=fqn.lastIndexOf('.');return i<0?"":fqn.substring(0,i);}

    static CallHierarchyExporter.Config cfg(Path dir,String name,String... extra) throws Exception {
        List<String> L=new ArrayList<>(List.of("project.root=.","entry.packages=p.*",
          "output.csv=./"+name+".csv","cache.file=./"+name+".tsv","resolutions.csv=./"+name+"-res.csv",
          "max.depth=6"));
        L.addAll(List.of(extra));
        Path f=dir.resolve(name+".properties");
        Files.writeString(f,String.join("\n",L),StandardCharsets.UTF_8);
        return new CallHierarchyExporter.Config(f);
    }

    public static void main(String[] a) throws Exception {
        Path d=Files.createTempDirectory("res"); Files.createDirectories(d.resolve("."));

        System.out.println("[1] 段1: 単一実装ショートカット / オーバーライドなし");
        // IF Dao <- UserDaoImpl のみ。 Svc#run -> Dao#exec (仮想)
        Path c1=d.resolve("t1.tsv");
        Files.write(c1,List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("p.Dao",'I'), H("p.UserDaoImpl",'C',"p.Dao"), H("p.Svc",'C'),
            D("p.Dao","exec",1,false),            // IFの抽象メソッド(本体なし)
            D("p.UserDaoImpl","exec",10,true),
            D("p.Svc","run",20,true),
            C("p.Svc","run","p.Dao","exec",21,'V')), StandardCharsets.UTF_8);
        CallHierarchyExporter.CallGraph g1=CallHierarchyExporter.CallGraph.buildFrom(c1);
        int dao=g1.methods.idOf("p.Dao#exec()");
        CallHierarchyExporter.CallGraph.Resolution r1=g1.resolve(dao,'V');
        check("SINGLE_IMPL に解決", "SINGLE_IMPL".equals(r1.label), r1.label);
        check("解決先が UserDaoImpl", g1.methods.typeFqn(r1.targets[0]).equals("p.UserDaoImpl"), g1.methods.typeFqn(r1.targets[0]));
        check("IFの抽象メソッドは候補外", r1.targets.length==1, ""+r1.targets.length);

        System.out.println("[2] 段0: 静的束縛（宣言型が具象でもサブクラスがある場合）");
        // Base <- Derived が override。仮想なら CHA、静的束縛なら Base 確定
        Path c2=d.resolve("t2.tsv");
        Files.write(c2,List.of("jche-cache-v1",String.join(T,"F","y.java","1","1"),
            H("p.Base",'C'), H("p.Derived",'C',"p.Base"), H("p.Mid",'C',"p.Base"), H("p.Leaf",'C',"p.Mid"),
            D("p.Base","m",1,true), D("p.Derived","m",5,true),
            D("p.Base","util",30,true),
            D("p.Svc","run",20,true), H("p.Svc",'C'),
            C("p.Svc","run","p.Base","m",21,'V'),
            C("p.Svc","run","p.Base","util",22,'S')), StandardCharsets.UTF_8);
        CallHierarchyExporter.CallGraph g2=CallHierarchyExporter.CallGraph.buildFrom(c2);
        CallHierarchyExporter.CallGraph.Resolution rv=g2.resolve(g2.methods.idOf("p.Base#m()"),'V');
        CallHierarchyExporter.CallGraph.Resolution rs=g2.resolve(g2.methods.idOf("p.Base#util()"),'S');
        check("具象宣言型でも仮想ならCHA", "CHA".equals(rv.label) && rv.targets.length==2, rv.label+"/"+rv.targets.length);
        check("静的束縛は宣言のまま確定", "STATIC_BOUND".equals(rs.label) && rs.targets.length==1, rs.label);
        System.out.println("[3] 候補数は「サブクラス数」でなく「オーバーライド数」");
        // Mid/Leaf は m をオーバーライドしていない -> 候補は Base,Derived の2件のみ
        check("Mid/Leaf は候補に入らない", rv.targets.length==2, ""+rv.targets.length);
        check("推移的サブタイプは3件", g2.transitiveSubtypes("p.Base").size()==3, ""+g2.transitiveSubtypes("p.Base"));

        System.out.println("[4] CHA未展開ポリシー（記録するが降りない）");
        CallHierarchyExporter.Config cf=cfg(d,"t2","cha.expand=false");
        Files.copy(c2,cf.cacheFile,StandardCopyOption.REPLACE_EXISTING);
        CallHierarchyExporter.CallGraph g4=CallHierarchyExporter.CallGraph.buildFrom(cf.cacheFile);
        CallHierarchyExporter.CallHierarchyCsvWriter w=new CallHierarchyExporter.CallHierarchyCsvWriter(cf.outputCsv,cf.outputEncoding,cf.outputBom,cf.outputDelimiter);
        long rows=new CallHierarchyExporter.StreamingTreeWalker(g4,cf,w).walkAll(new int[]{g4.methods.idOf("p.Svc#run()")});
        w.close();
        List<String> L=Files.readAllLines(cf.outputCsv, java.nio.charset.Charset.forName("MS932"));
        L.forEach(x->System.out.println("      "+x));
        check("CHA候補が1行だけ記録される", L.stream().filter(x->x.contains("CHA候補2件（未展開）")).count()==1,"");
        check("静的束縛は普通に展開", L.stream().anyMatch(x->x.contains("Base.util")),"");

        System.out.println("[5] CHA展開ポリシー");
        CallHierarchyExporter.Config cf5=cfg(d,"t5","cha.expand=true","cha.max.candidates=20");
        Files.copy(c2,cf5.cacheFile,StandardCopyOption.REPLACE_EXISTING);
        CallHierarchyExporter.CallGraph g5=CallHierarchyExporter.CallGraph.buildFrom(cf5.cacheFile);
        CallHierarchyExporter.CallHierarchyCsvWriter w5=new CallHierarchyExporter.CallHierarchyCsvWriter(cf5.outputCsv,cf5.outputEncoding,cf5.outputBom,cf5.outputDelimiter);
        new CallHierarchyExporter.StreamingTreeWalker(g5,cf5,w5).walkAll(new int[]{g5.methods.idOf("p.Svc#run()")}); w5.close();
        List<String> L5=Files.readAllLines(cf5.outputCsv, java.nio.charset.Charset.forName("MS932"));
        check("Base.m と Derived.m の両方が出る",
            L5.stream().anyMatch(x->x.contains("Base.m"))&&L5.stream().anyMatch(x->x.contains("Derived.m")),"");
        check("CHA候補である旨の注記", L5.stream().anyMatch(x->x.contains("CHA候補2件中")),"");

        System.out.println("[6] 解決レポート");
        CallHierarchyExporter.ResolutionStats st=CallHierarchyExporter.ResolutionReport.write(g5,cf5);
        System.out.println("      "+st);
        check("静的束縛1件", st.staticBound==1, ""+st.staticBound);
        check("CHA1件", st.cha==1, ""+st.cha);
        List<String> R=Files.readAllLines(cf5.resolutionsCsv, java.nio.charset.Charset.forName("MS932"));
        check("resolutions.csv 出力", R.size()>=2 && R.get(1).contains("CHA"), R.toString());

        System.out.println("[7] 匿名クラスによるオーバーライドも候補に入る");
        Path c7=d.resolve("t7.tsv");
        Files.write(c7,List.of("jche-cache-v1",String.join(T,"F","z.java","1","1"),
            H("p.Task",'I'), H("p.Outer$1",'C',"p.Task"), H("p.Outer",'C'),
            D("p.Task","run",1,false), D("p.Outer$1","run",8,true), D("p.Outer","go",5,true),
            C("p.Outer","go","p.Task","run",6,'V')), StandardCharsets.UTF_8);
        CallHierarchyExporter.CallGraph g7=CallHierarchyExporter.CallGraph.buildFrom(c7);
        CallHierarchyExporter.CallGraph.Resolution r7=g7.resolve(g7.methods.idOf("p.Task#run()"),'V');
        check("匿名クラスに解決", "SINGLE_IMPL".equals(r7.label)
            && g7.methods.typeFqn(r7.targets[0]).equals("p.Outer$1"), r7.label);

        System.out.println("[8] 循環する型階層でも停止する");
        Path c8=d.resolve("t8.tsv");
        Files.write(c8,List.of("jche-cache-v1",String.join(T,"F","w.java","1","1"),
            H("p.A",'C',"p.B"), H("p.B",'C',"p.A"), D("p.A","m",1,true)), StandardCharsets.UTF_8);
        CallHierarchyExporter.CallGraph g8=CallHierarchyExporter.CallGraph.buildFrom(c8);
        check("無限ループしない", g8.transitiveSubtypes("p.A").size()<=2, ""+g8.transitiveSubtypes("p.A"));

        System.out.println("\n=== "+(ng==0?"全項目OK":ng+"件 NG")+" ===");
        if(ng>0) System.exit(1);
    }
}
