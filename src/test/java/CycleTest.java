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
import java.nio.file.*; import java.nio.charset.*; import java.util.*;

/** [CYCLE] の経路単位判定を検証 */
public class CycleTest {
    static int ng=0;
    static void check(String l,boolean ok,String d){System.out.println((ok?"  OK   ":"  NG   ")+l+(ok?"":"  -> "+d)); if(!ok)ng++;}
    static final String T="\t";
    static String H(String ty){return String.join(T,"H",ty,"C","");}
    static String D(String ty,String m,int ln){int i=ty.lastIndexOf('.');
        return String.join(T,"D",i<0?"":ty.substring(0,i),ty,m,"",""+ln,"1");}
    static String C(String ct,String cm,String et,String em,int ln){int i=ct.lastIndexOf('.'),j=et.lastIndexOf('.');
        return String.join(T,"C",i<0?"":ct.substring(0,i),ct,cm,"",j<0?"":et.substring(0,j),et,em,"",""+ln,"V","");}

    static List<String> run(Path d,String name,List<String> cache,String... extra) throws Exception {
        Files.write(d.resolve(name+".tsv"),cache,StandardCharsets.UTF_8);
        List<String> props=new ArrayList<>(List.of("project.root=.","entry.packages=p.*",
            "cache.file=./"+name+".tsv","output.csv=./"+name+".csv","resolutions.csv=./"+name+"r.csv"));
        props.addAll(List.of(extra));
        Files.writeString(d.resolve(name+".properties"),String.join("\n",props),StandardCharsets.UTF_8);
        CallHierarchyExporter.Config cf=new CallHierarchyExporter.Config(d.resolve(name+".properties"));
        CallHierarchyExporter.CallGraph g=CallHierarchyExporter.CallGraph.buildFrom(cf.cacheFile);
        int[] roots={g.methods.idOf("p.Root#run()")};
        CallHierarchyExporter.CallHierarchyCsvWriter w=new CallHierarchyExporter.CallHierarchyCsvWriter(cf.outputCsv,cf.outputEncoding);
        new CallHierarchyExporter.StreamingTreeWalker(g,cf,w).walkAll(roots); w.close();
        List<String> L=Files.readAllLines(cf.outputCsv,Charset.forName("MS932"));
        L.forEach(x->System.out.println("      "+x));
        return L;
    }

    public static void main(String[] a) throws Exception {
        Path d=Files.createTempDirectory("cyc");

        System.out.println("[1] 直接の自己再帰");
        List<String> L1=run(d,"t1",List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("p.Root"),H("p.A"),
            D("p.Root","run",1), D("p.A","f",10),
            C("p.Root","run","p.A","f",2), C("p.A","f","p.A","f",11)),"max.depth=6");
        check("[CYCLE]が1件出る", L1.stream().filter(x->x.contains("[CYCLE]")).count()==1,"");
        check("無限ループしない", L1.size()<10, ""+L1.size());

        System.out.println("[2] 相互再帰 A→B→A");
        List<String> L2=run(d,"t2",List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("p.Root"),H("p.A"),H("p.B"),
            D("p.Root","run",1), D("p.A","f",10), D("p.B","g",20),
            C("p.Root","run","p.A","f",2), C("p.A","f","p.B","g",11), C("p.B","g","p.A","f",21)),"max.depth=6");
        check("A→B→A の戻りが[CYCLE]", L2.stream().anyMatch(x->x.contains("[CYCLE]")&&x.contains("A.f")),"");

        System.out.println("[3] 別経路の同じ呼び出しは抑制されない（経路単位判定）");
        // Root -> X -> A -> A(cycle) と Root -> Y -> A -> A(cycle) の2経路
        List<String> L3=run(d,"t3",List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("p.Root"),H("p.X"),H("p.Y"),H("p.A"),
            D("p.Root","run",1), D("p.X","x",10), D("p.Y","y",20), D("p.A","f",30),
            C("p.Root","run","p.X","x",2), C("p.Root","run","p.Y","y",3),
            C("p.X","x","p.A","f",11), C("p.Y","y","p.A","f",21),
            C("p.A","f","p.A","f",31)),"max.depth=6");
        long cyc=L3.stream().filter(x->x.contains("[CYCLE]")).count();
        check("2経路それぞれで[CYCLE]が出る", cyc==2, "件数="+cyc);
        check("経路XのCYCLE行がある", L3.stream().anyMatch(x->x.contains("[CYCLE]")&&x.contains("Root.run,X.x")),"");
        check("経路YのCYCLE行がある", L3.stream().anyMatch(x->x.contains("[CYCLE]")&&x.contains("Root.run,Y.y")),"");

        System.out.println("[4] ダイヤモンド（循環でない再訪）は[CYCLE]にしない");
        // Root -> X -> A, Root -> Y -> A （Aは2回出るが循環ではない）
        List<String> L4=run(d,"t4",List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("p.Root"),H("p.X"),H("p.Y"),H("p.A"),
            D("p.Root","run",1), D("p.X","x",10), D("p.Y","y",20), D("p.A","f",30),
            C("p.Root","run","p.X","x",2), C("p.Root","run","p.Y","y",3),
            C("p.X","x","p.A","f",11), C("p.Y","y","p.A","f",21)),"max.depth=6");
        check("A.f が2回出る", L4.stream().filter(x->x.split(",",-1)[1].equals("A.f")).count()==2,"");
        check("[CYCLE]は出ない", L4.stream().noneMatch(x->x.contains("[CYCLE]")),"");

        System.out.println("[5] CYCLE行の中身");
        String row=L3.stream().filter(x->x.contains("[CYCLE]")).findFirst().get();
        String[] f=row.split(",",-1);
        check("callee列に戻り先メソッドが入る", f[1].equals("A.f"), f[1]);
        check("note列が[CYCLE]", f[2].equals("[CYCLE]"), f[2]);
        check("caller列は呼び出し箇所", f[0].contains(":31"), f[0]);
        // CYCLE行で打ち切られるので、経路上に A.f が3回以上現れる行は存在しないはず
        int maxInPath=0;
        for(int i=1;i<L3.size();i++){
            String[] g=L3.get(i).split(",",-1);
            int c=0; for(int j=3;j<g.length;j++) if(g[j].equals("A.f")) c++;
            maxInPath=Math.max(maxInPath,c);
        }
        check("CYCLEの先へは降りない(経路上A.fは最大2回)", maxInPath==2, "max="+maxInPath);

        System.out.println("\n=== "+(ng==0?"全項目OK":ng+"件 NG")+" ===");
        if(ng>0) System.exit(1);
    }
    static int countOcc(String s,String t){int c=0,i=0;while((i=s.indexOf(t,i))>=0){c++;i+=t.length();}return c;}
}
