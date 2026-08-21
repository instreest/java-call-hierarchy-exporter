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
package jp.co.example.callhierarchy;
import jp.co.example.callhierarchy.CallHierarchyExporter.*;
import java.nio.file.*; import java.nio.charset.*; import java.util.*;

/** 全体モード（entry.packages 未指定）の検証 */
public class WholeProjectTest {
    static int ng=0;
    static void check(String l,boolean ok,String d){System.out.println((ok?"  OK   ":"  NG   ")+l+(ok?"":"  -> "+d)); if(!ok)ng++;}
    static final String T="\t";
    static String H(String ty,char k,String... sup){return String.join(T,"H",ty,""+k,String.join(",",sup));}
    static String D(String ty,String m,int ln,boolean body){int i=ty.lastIndexOf('.');
        return String.join(T,"D",i<0?"":ty.substring(0,i),ty,m,"",""+ln,body?"1":"0");}
    static String C(String ct,String cm,String et,String em,int ln){int i=ct.lastIndexOf('.'),j=et.lastIndexOf('.');
        return String.join(T,"C",i<0?"":ct.substring(0,i),ct,cm,"",j<0?"":et.substring(0,j),et,em,"",""+ln,"V","");}

    public static void main(String[] a) throws Exception {
        Path d=Files.createTempDirectory("wp");
        Path cache=d.resolve("c.tsv");
        String ACT="p.act.OrderAction", SVC="p.svc.OrderService", IF="p.dao.OrderDao",
               IMPL="p.dao.OrderDaoImpl", UTIL="p.util.StrUtil", DEAD="p.old.LegacyBatch",
               CYC1="p.cyc.A", CYC2="p.cyc.B";
        List<String> L=new ArrayList<>(List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
          H(ACT,'C'),H(SVC,'C'),H(IF,'I'),H(IMPL,'C',IF),H(UTIL,'C'),H(DEAD,'C'),H(CYC1,'C'),H(CYC2,'C'),
          D(ACT,"execute",10,true), D(SVC,"find",20,true),
          D(IF,"select",30,false), D(IMPL,"select",40,true),
          D(UTIL,"trim",50,true), D(DEAD,"run",60,true),
          D(CYC1,"f",70,true), D(CYC2,"g",80,true),
          C(ACT,"execute",SVC,"find",11),
          C(SVC,"find",IF,"select",21),          // IF経由 -> SINGLE_IMPL で IMPL に解決
          C(SVC,"find",UTIL,"trim",22),
          C(IMPL,"select",UTIL,"trim",41),
          C(DEAD,"run",UTIL,"trim",61),          // 誰からも呼ばれないメソッド
          C(CYC1,"f",CYC2,"g",71), C(CYC2,"g",CYC1,"f",81)));  // 相互再帰＝到達不能
        // HUB判定用に trim を大量に呼ぶ
        for(int i=0;i<25;i++){ L.add(D("p.h.H"+i,"m",1,true)); L.add(H("p.h.H"+i,'C')); L.add(C("p.h.H"+i,"m",UTIL,"trim",2)); }
        Files.write(cache,L,StandardCharsets.UTF_8);

        Path cfg=d.resolve("c.properties");
        Files.writeString(cfg,String.join("\n","project.root=.","cache.file=./c.tsv",
          "output.csv=./ch.csv","methods.csv=./m.csv","edges.csv=./e.csv","resolutions.csv=./r.csv",
          "hub.threshold=20"),StandardCharsets.UTF_8);
        Config cf=new Config(cfg);
        check("entry.packages未指定で全体モード", cf.wholeProjectMode, "");

        CallGraph g=CallGraph.buildFrom(cache);
        int[] roots=g.selectEntryPoints(cf);
        Set<String> rootNames=new TreeSet<>();
        for(int r:roots) rootNames.add(g.methods.shortLabel(r));
        System.out.println("      起点候補="+rootNames);
        check("入次数0が起点になる", rootNames.contains("OrderAction.execute"), rootNames.toString());
        check("デッドコードも起点に混ざる", rootNames.contains("LegacyBatch.run"), "");
        check("IF経由で呼ばれる実装は起点にならない(解決後の入次数)",
            !rootNames.contains("OrderDaoImpl.select"), rootNames.toString());
        check("相互再帰は起点にならない", !rootNames.contains("A.f")&&!rootNames.contains("B.g"), rootNames.toString());

        InventoryStats st=InventoryReport.writeMethods(g,cf,roots);
        long edges=InventoryReport.writeEdges(g,cf);
        System.out.println("      "+st+" / edges="+edges);

        Charset ms=Charset.forName("MS932");
        List<String> M=Files.readAllLines(cf.methodsCsv,ms);
        Map<String,String[]> byMethod=new LinkedHashMap<>();
        for(int i=1;i<M.size();i++){String[] f=M.get(i).split(",",-1); byMethod.put(f[0],f);}
        System.out.println("      "+M.get(0));
        for(String k:List.of("OrderAction.execute","OrderDaoImpl.select","StrUtil.trim","LegacyBatch.run","A.f"))
            System.out.println("      "+String.join(",",byMethod.get(k)));

        System.out.println("[role判定]");
        check("入口候補=ENTRY_CANDIDATE", byMethod.get("OrderAction.execute")[8].equals("ENTRY_CANDIDATE"), byMethod.get("OrderAction.execute")[8]);
        check("多数から呼ばれる=HUB", byMethod.get("StrUtil.trim")[8].equals("HUB"), byMethod.get("StrUtil.trim")[8]);
        check("デッドコードも入口候補として現れる", byMethod.get("LegacyBatch.run")[8].equals("ENTRY_CANDIDATE"), "");
        check("相互再帰は到達不能として印がつく", byMethod.get("A.f")[9].equals("0"), byMethod.get("A.f")[9]);
        check("到達できるものは1", byMethod.get("OrderDaoImpl.select")[9].equals("1"), "");
        check("本体なしIFメソッドも一覧に出る", byMethod.containsKey("OrderDao.select"), "");
        check("hasBody=0で区別できる", byMethod.get("OrderDao.select")[5].equals("0"), byMethod.get("OrderDao.select")[5]);

        System.out.println("[edges.csv]");
        List<String> E=Files.readAllLines(cf.edgesCsv,ms);
        System.out.println("      "+E.get(0));
        E.stream().filter(x->x.startsWith("OrderService.find")).forEach(x->System.out.println("      "+x));
        check("解決後のcalleeが入る", E.stream().anyMatch(x->x.startsWith("OrderService.find,OrderDaoImpl.select,")), "");
        check("declaredCalleeにIFが残る", E.stream().anyMatch(x->x.contains("p.dao.OrderDao#select()")), "");
        check("resolution列がある", E.stream().anyMatch(x->x.contains("SINGLE_IMPL")), "");
        check("エッジ数は線形", edges==E.size()-1, edges+"/"+(E.size()-1));

        System.out.println("[entry.auto=false]");
        Path cfg2=d.resolve("c2.properties");
        Files.writeString(cfg2,String.join("\n","project.root=.","cache.file=./c.tsv",
          "output.csv=./ch2.csv","entry.auto=false"),StandardCharsets.UTF_8);
        Config cf2=new Config(cfg2);
        check("起点0になる", CallGraph.buildFrom(cache).selectEntryPoints(cf2).length==0, "");

        System.out.println("\n=== "+(ng==0?"全項目OK":ng+"件 NG")+" ===");
        if(ng>0) System.exit(1);
    }
}
