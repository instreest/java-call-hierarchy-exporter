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
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;

/** 出力形式(caller,callee,note,callHierarchy)と MS932 出力の検証 */
public class OutputFormatTest {
    static int ng=0;
    static void check(String l,boolean ok,String d){System.out.println((ok?"  OK   ":"  NG   ")+l+(ok?"":"  -> "+d)); if(!ok)ng++;}
    static final String T="\t";
    static String D(String ty,String m,int ln){int i=ty.lastIndexOf('.');
        return String.join(T,"D",i<0?"":ty.substring(0,i),ty,m,"",""+ln,"1");}
    static String C(String ct,String cm,String et,String em,int ln){int i=ct.lastIndexOf('.'),j=et.lastIndexOf('.');
        return String.join(T,"C",i<0?"":ct.substring(0,i),ct,cm,"",j<0?"":et.substring(0,j),et,em,"",""+ln,"V","");}
    static String H(String ty,char k,String... sup){return String.join(T,"H",ty,""+k,String.join(",",sup));}

    public static void main(String[] a) throws Exception {
        Path d=Files.createTempDirectory("of");
        Path cache=d.resolve("c.tsv");
        Files.write(cache,List.of("jche-cache-v1",String.join(T,"F","x.java","1","1"),
            H("jp.co.xxx.action.UserAction",'C'), H("jp.co.xxx.service.UserService",'C'),
            H("jp.co.xxx.dao.UserDaoImpl",'C'),
            D("jp.co.xxx.action.UserAction","execute",45),
            D("jp.co.xxx.service.UserService","findUser",23),
            D("jp.co.xxx.dao.UserDaoImpl","findUserById",67),
            C("jp.co.xxx.action.UserAction","execute","jp.co.xxx.service.UserService","findUser",50),
            C("jp.co.xxx.service.UserService","findUser","jp.co.xxx.dao.UserDaoImpl","findUserById",30)),
            StandardCharsets.UTF_8);
        Path cfg=d.resolve("c.properties");
        Files.writeString(cfg,String.join("\n","project.root=.","entry.packages=jp.co.xxx.action.*",
            "output.csv=./call-hierarchy.csv","cache.file=./c.tsv","resolutions.csv=./r.csv",
            "unresolved.csv=./u.csv"),StandardCharsets.UTF_8);
        Config cf=new Config(cfg);
        CallGraph g=CallGraph.buildFrom(cache);
        CallHierarchyCsvWriter w=new CallHierarchyCsvWriter(cf.outputCsv,cf.outputEncoding);
        new StreamingTreeWalker(g,cf,w).walkAll(g.selectEntryPoints(cf)); w.close();

        Charset ms932=Charset.forName("MS932");
        List<String> L=Files.readAllLines(cf.outputCsv,ms932);
        L.forEach(x->System.out.println("      "+x));

        System.out.println("[1] ヘッダーと列構成");
        check("ヘッダー=caller,callee,note,callHierarchy",
            L.get(0).equals("caller,callee,note,callHierarchy"), L.get(0));
        String[] f2=L.get(2).split(",",-1);
        check("caller列はスタックトレース形式",
            f2[0].matches("^at jp\\.co\\.xxx\\.action\\.UserAction\\.execute\\([\\w.]+:50\\)$"), f2[0]);
        check("callee列は短縮表記(フィルタ可)", f2[1].equals("UserService.findUser"), f2[1]);
        check("root行はcallerが空", L.get(1).startsWith(","), L.get(1));

        System.out.println("[2] Excelフィルタ: callee列の値が安定している");
        Set<String> callees=new LinkedHashSet<>();
        for(int i=1;i<L.size();i++) callees.add(L.get(i).split(",",-1)[1]);
        System.out.println("      callee候補="+callees);
        check("行番号を含まない", callees.stream().noneMatch(x->x.matches(".*:\\d+.*")), callees.toString());
        check("呼び出し箇所ごとに散らばらない", callees.size()==3, ""+callees.size());

        System.out.println("[3] grep 末尾一致");
        check("末尾がcallHierarchy最終要素",
            L.get(3).endsWith("UserDaoImpl.findUserById"), L.get(3));
        long hit=L.stream().filter(x->x.endsWith("UserDaoImpl.findUserById")).count();
        check("grep \"findUserById$\" で1行ヒット", hit==1, ""+hit);

        System.out.println("[4] 文字コード MS932");
        byte[] raw=Files.readAllBytes(cf.outputCsv);
        check("MS932で復号できる", new String(raw,ms932).contains("caller,callee"), "");
        // 日本語ノートを含む行を作って往復確認
        Path p2=d.resolve("jp.csv");
        java.io.BufferedWriter bw=Csv.writer(p2,ms932);
        bw.write("循環参照のため打ち切り,深さ制限,ソースなし（展開不可）"); bw.newLine(); bw.close();
        byte[] jb=Files.readAllBytes(p2);
        check("日本語がMS932バイト列になる",
            new String(jb,ms932).startsWith("循環参照") && !new String(jb,StandardCharsets.UTF_8).startsWith("循環参照"),
            "len="+jb.length);
        check("UTF-8より短い(=SJIS相当)", jb.length < "循環参照のため打ち切り,深さ制限,ソースなし（展開不可）".getBytes(StandardCharsets.UTF_8).length+2, ""+jb.length);

        System.out.println("[5] 変換不可文字でも落ちない");
        Path p3=d.resolve("emoji.csv");
        java.io.BufferedWriter bw3=Csv.writer(p3,ms932);
        bw3.write("A\uD83D\uDE00B"); bw3.newLine(); bw3.close();
        check("例外にならず置換される", Files.readAllBytes(p3).length>0, "");

        System.out.println("[6] 他のCSVも同じ文字コード");
        ResolutionReport.write(g,cf);
        check("resolutions.csv も読める",
            Files.readAllLines(cf.resolutionsCsv,ms932).get(0).startsWith("declaredMethod"), "");

        System.out.println("\n=== "+(ng==0?"全項目OK":ng+"件 NG")+" ===");
        if(ng>0) System.exit(1);
    }
}
