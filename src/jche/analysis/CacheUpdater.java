// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import jche.cache.BlockChecksum;
import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallSite;
import jche.cache.CallSiteValues;
import jche.cache.ConstantFact;
import jche.cache.FieldAccessFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.Guard;
import jche.cache.HintFact;
import jche.cache.LibraryFact;
import jche.cache.MethodDeclFact;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TempFiles;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.cache.ValueNode;
import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.FileHash;
import jche.util.Log;
import jche.util.Progress;
import jche.util.RunControl;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * フェーズ1: 旧キャッシュを先頭から読みながら新キャッシュを書き出す、ストリーミングマージ。
 *
 * 1ファイル分の結果が出来るたびにキャッシュファイルへ直接書き出して破棄するため、
 * 全件保持は不要。ヒープ常駐は「ソースファイルの一覧＋サイズ」「変わった型の集合」と、
 * 書き写すブロックの位置（ブロックあたり数十バイトの配列）だけ。未解決呼び出しの件数も、
 * この過程で同時に数える（溜め込まない）。
 * パース自体は {@link CallEdgeExtractor#BATCH_SIZE} 件ずつまとめて行う（1ファイルずつでは
 * 規模に比例して遅くなるため）。
 *
 * <h2>キャッシュは 1 ファイル、壊れたかどうかはブロックごとに見る</h2>
 * キャッシュ（{@code analysis-cache.tsv}）は、ソースファイル 1 つにつき 1 ブロックで、構造と値を
 * 同じブロックに持つ（{@link jche.cache.CacheFormat}）。一時ファイルに書いてから本物に差し替える。
 * F 行にはブロックの検査値（crc。F 行自身の件数の列も含む）を書き、パス1 はブロックごとに計算し直して突き合わせる。
 * 先頭の行（ヘッダ・L 行・T 行）の検査値は T 行の最後の列にあり、合わなければ丸ごと捨てる。
 * 合わないブロック（書き換え・文字化け）は、ソースが変わったブロックと同じく無効にする。そのファイルを解析し直し、
 * ブロックが宣言していた型（H 行）を「変わった型」にして、その型を使うファイルも解析し直す（壊れたブロックからは
 * 前回の事実の何が変わったかを言えないため）。ほかのブロックは再利用する。
 * 最終行（Z 行）のブロック数が合わない・読めないキャッシュは、丸ごと捨てて全件解析し直す。
 * 以前の形式が残した {@code dataflow-cache.tsv}（とその一時ファイル）は、実行の最初に消す。
 *
 * 手順:
 * <pre>
 *   パス0 … 旧キャッシュのヘッダ（形式・ソースレベル・文字コード・JDK・JDT・ソースフォルダの並び。フォルダを足した・
 *           外しただけなら使い続ける。{@link CacheFormat#headerReusable}）と
 *           先頭の行の検査値（T 行の最後の列）を検証し、
 *           L 行（解析時の依存 jar）を読んで今回のクラスパスと突き合わせる。
 *           追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。
 *           ソースフォルダか jar が変わっていれば、JDT が今回のクラスパスを受け付けるかを確かめ、
 *           受け付けなければ旧キャッシュを使わない（{@link #environmentStillAccepted}）。
 *   パス1 … 旧キャッシュを順に読み、サイズと内容ハッシュが一致し、検査値も合うファイル（有効）を覚える。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           どのブロックの H 行の親型も部分型の索引に足す（下記「親型の連鎖」）。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *           宣言の連鎖のために、有効なブロックの自分の宣言の指紋（I 行の 3 列目）もここで覚える。
 *           今のソースに無いファイルと同じコンパイル単位の名前のファイルも有効から外す
 *           （{@link SameUnitFiles#pairedWithDeleted}）。
 *           旧キャッシュを行として読むのはこの 1 回だけ。有効なブロックの依存（I 行）は一時ファイル
 *           （依存の索引）に書き、ファイル上の範囲と F 行の件数は配列に覚えておく（パス3・パス5 が使う）。
 *   （何も変わっていなければ、ここで終わる。下記「何も変わっていないとき」）
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。H 行の親型は部分型の索引に足す。
 *           前回どのブロックも宣言していなかった型は「新しい型」としても覚える（下記「新しい型」）。
 *   パス3 … 依存の索引を読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」または「変わったパッケージ」に触れるものを再解析に回す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるため。
 *           新しい型があれば、型解決に失敗していたブロックのうち解決できなかった名前が新しい型に当たるものと、
 *           新しい型に名前を隠されうるブロックも回す。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。そのファイルの自分の宣言の指紋（宣言と定数の値）が
 *           旧キャッシュと違っていたら、または旧キャッシュで変わった jar に触れていたなら、宣言する型を「変わった型」に加えて
 *           パス3へ戻る（下記「宣言の連鎖」）。「変わった型」は、どの時点でも部分型で閉じている（下記「親型の連鎖」）。
 *           「変わった型」が増えなくなるまで繰り返す。
 *   パス5 … 最後まで有効だったブロックを、F 行ごとそのまま書き写す（行に戻さず、バイトの範囲のまま）。
 * </pre>
 *
 * <h2>旧キャッシュの読み方（1 つのチャネル）</h2>
 * 旧キャッシュは実行の最初に 1 回だけ開き（{@link #openOldCache}）、パス0 のヘッダ、パス1 の全体、パス3 の I 行
 * （索引を書けなかったとき）、パス5 の書き写しまで、同じチャネルで読む。パス5 はパス1 で覚えたバイトの範囲を
 * そのまま写すので、名前で開き直すと、そのあいだに差し替えられた別のファイルの、関係の無い範囲を写しかねない。
 * 読むたびに大きさが開いたときのままかを確かめ、違えば止める（{@link #checkOldCacheUnchanged}。パス1 の中なら
 * 読めないキャッシュとして丸ごと捨て、パス3・パス5 なら解析を失敗にする）。
 * 同じキャッシュのフォルダを使う実行は、フェーズ1 とフェーズ2 のあいだ錠で 1 つずつにしてある
 * （{@link jche.cache.CacheLock}。{@code jche.Exporter} が取る）ので、ふつうは差し替えも書き換えも起きない。
 *
 * <h2>実行中に書き換えられたソース</h2>
 * 内容ハッシュはパス1 で取り、JDT はそのあとでファイルを読む。そのあいだにソースが書き換えられると、前の中身の
 * ハッシュと後の中身の事実が組になる。そのあとで元に戻すと、ハッシュが一致して書き換えた中身の事実を再利用し
 * 続けていた。そこで
 * <ul>
 *   <li>解析したファイルは、JDT が読んだ直後にハッシュを取り直し、前と違えば F 行の内容ハッシュを空にして書く
 *       （{@link BlockWriter#hashAfterParse}。空のハッシュはどの中身とも一致しない）</li>
 *   <li>書き終えたら、どのソースも一覧を作ったときの大きさと更新時刻のままかを見て、変わったファイルのブロックの
 *       内容ハッシュも空にする（{@link #invalidateChangedDuringRun}。JDT はほかのファイルを解析するときにも
 *       ソースパスから読むので、再利用したブロックのファイルでも、その中身を読んだファイルがある）</li>
 *   <li>解析のあいだにソースが消えた（読めない）・一覧に無かったソースが増えたら、そのブロックと、この実行で解析した
 *       ファイルのブロックの内容ハッシュも空にする（消したファイルを同じ中身で戻す・足したファイルを消すと、どのブロックにも
 *       痕跡が残らないため）</li>
 * </ul>
 * 次の実行はそれらを解析し直し、宣言する型を「変わった型」にして、依存するファイルも解析し直す。
 * 更新時刻は実行のあいだの見張りにだけ使い、キャッシュには書かない（下の「同一性」は変えない）。
 *
 * <p>依存 jar・クラスフォルダも同じで、指紋（L 行）はパス0 で取り、JDT はそのあとバッチごとに読む。書き終えたら、
 * どれもパス0 で見たときの見かけ（大きさ・更新時刻など）のままかを見て、変わっていれば<b>この実行で解析したファイル</b>の
 * ブロックの内容ハッシュを空にする（{@link #invalidateClasspathChangedDuringRun}。どのファイルが書き換えた中身を
 * 読んだかは分からないので、解析したものすべて。再利用したブロックは前の実行でパス0 の指紋と同じ中身から作ったもの
 * なので残す）。兄弟モジュールの clean ビルドのように、解析のあいだに書き換えて同じ中身に戻すと、指紋は一致するのに
 * 書き換えた中身の事実が残り続けていた。
 *
 * <h2>何も変わっていないとき</h2>
 * 解析するファイルが 1 つも無く（変更・追加・削除・壊れたブロック・jar の変化が無い）、先頭の行
 * （ヘッダ・L 行・T 行）も同じなら、書き直しても旧キャッシュとまったく同じバイト列になる。
 * そのときは書き直さず、旧キャッシュのファイルをそのまま残す（{@link #canKeepAsIs}）。件数の数え方は
 * 書き直したときと同じ。旧キャッシュが書き手の書くとおりの形でない（空行・CRLF を含む）ときは、
 * 書き直すとバイト列が変わるので残さない。
 *
 * <h2>一時ファイル</h2>
 * キャッシュ本体の一時ファイル（{@code .tmp}。中断からの引き継ぎに使う）のほかに、パス1 が書いて
 * パス3 が読む依存の索引（{@link DepsIndex}。{@link TempFiles}）を使う。索引は実行の終わりに（失敗しても）消し、
 * 強制終了（SIGINT / SIGTERM）のときは JVM の終了フックが消し、それでも残ったものは実行の最初に消す。
 * 索引を書けない（ディスクの空きが無い・権限が無い）ときは、パス3 は旧キャッシュから I 行を読む
 * （索引を使う前と同じ読み方。結果は変わらない）。
 *
 * <h2>同一性（何をもって「同じファイル」とみなすか）</h2>
 * <b>相対パス・サイズ・内容ハッシュ</b>の3つで見る。更新時刻は記録も参照もしない。
 * 更新時刻は中身と関係なく変わる（git のチェックアウト、コピー、CI のたびに作り直される
 * ワークスペース）ので、当てにすると「中身は同じなのにキャッシュを捨てる」が起きる。
 * 逆に、バージョン管理が更新時刻を復元する設定だと「中身が違うのに再利用する」も起きうる。
 * どちらも内容ハッシュなら起きない。同じ考え方を、依存 jar（L行、{@link LibraryDiff}）と
 * ソース一覧の指紋（T行、{@link #fingerprintOf}）にも通している。
 *
 * <p>そのファイル自身の同一性が一致しても、それだけでは再利用できない。別のファイルの変更
 * （オーバーロードの追加、フィールドの改名、親型の変更など）でこのファイルの解決結果が
 * 変わりうるため、下の依存（I行）の突き合わせが要る。
 *
 * <h2>宣言の連鎖（依存を 1 段で済ませられない場合の 1 つ目）</h2>
 * 依存（I 行）を 1 段辿るだけでは足りない場合が 3 つある。宣言の連鎖（この節）、親型の連鎖、新しい型（下の 2 節）。
 * ファイルAを解析し直しても、Aのソースが変わっていなければ、Aが宣言する型の名前は変わらない。それでも
 * Aに依存するファイルの事実が変わりうるのは、Aの事実のうち、Aが参照した別の型から持ち込んだもの（宣言に書いた型の
 * 解決先・定数の値、継承したメンバー。前の 2 つ）が、Aを使う側にも効くときである。3 つ目は、I 行にそもそも載らない型
 * （前回は無かった型・見えなかった型）の変化である。
 *
 * <p>宣言に書いた型の名前の解決先は、Aのソースが同じでも変わる。{@code import q.*} の {@code Foo} は、同じパッケージに
 * {@code p.Foo} ができると {@code p.Foo} になる（JLS 6.4.1）。すると A のメソッドの引数・戻り値の型、フィールドの型が変わり、
 * A を呼ぶ側のオーバーロードの選び方・式の型が変わる。そこで、書き手は I 行の 3 列目に自分の宣言の指紋（宣言する型・
 * メソッド・フィールドの JDT のバインディングの鍵と修飾子・インターフェースの関数型と、K 行の指紋。
 * {@link jche.cache.FileAnalysis#declarationKeys}）を
 * 書き、パス4 で解析し直した結果がこれと違えば、そのファイルが宣言する型も「変わった型」に加えてパス3からやり直す
 * （docs/cache-unification-qa.md の Q83）。継承したものは入れない（親の変化は「親型の連鎖」で届く）。
 * 旧キャッシュの I 行（か解決できなかった名前）が変わった jar のパッケージに触れていたファイルの型は、指紋に関わらず、
 * どの理由で選ばれたか（ソースの変化にも触れていた・名前が当たった・同じ名前のファイルの組）にも関わらず
 * 「変わった型」に加える（jar の親の親から継承したものは指紋にも H 行にも現れない。Q84・Q89）。
 * sealed な型かアノテーション型を宣言するファイルも、解析し直したら指紋に関わらず「変わった型」に加える。使う側の
 * 事実（switch の網羅性・キャストと instanceof・注釈を付けられる場所と繰り返し）は、許した部分型（入れ子の sealed の
 * 先まで）の宣言やメタ注釈の解決先・{@code @Repeatable} の入れ物の型に依るが、それらの名前を書いているのは宣言した
 * ファイルの側（permits・メタ注釈）だけなので、その変化はそのファイルを解析し直す形でしか届かない
 * （{@link jche.cache.FileAnalysis#cascadesWhenReanalysed}）。
 *
 * <p>コンパイル時定数（{@code static final} の値）は、
 * <b>使う側のファイルに値そのものが焼き込まれる</b>（Javaの言語仕様どおり、JDTもそう解決する）。
 * <pre>
 *   P.java   static final String KIND = "ALPHA";
 *   X.java   static final String KIND = P.KIND;     ← 値 "ALPHA" が焼き込まれる
 *   C.java   if (X.KIND.equals("BETA")) { … }       ← ここにも "ALPHA" が焼き込まれる
 * </pre>
 * C.java が参照している型は X だけなので、P.java を変えても C.java の I 行には引っかからず、
 * 古い "ALPHA" が残ってしまう（条件分岐の打ち切りや、クラス名の文字列からの具象クラスの
 * 特定が、古い値のまま出る）。
 *
 * <p>そこで、宣言している定数の値を K 行（{@link jche.cache.ConstantFact}）として残し、その指紋を自分の宣言の指紋に
 * 入れる。パス4で解析し直した結果その値が変わっていたら、指紋が変わるので、そのファイルが宣言する型も
 * 「変わった型」に加えてパス3からやり直す。連鎖するのは宣言か値が実際に変わったファイルを参照しているファイルだけ
 * なので、全件再解析にはならず、何も変わらなければ1周で止まる。
 *
 * <h2>親型の連鎖（継承したものは I 行に載らない。依存を 1 段で済ませられない場合の 2 つ目）</h2>
 * {@code D extends E}、{@code E extends F} のとき、D を使う側の I 行には D しか載らない。だがその事実
 * （継承したメソッドへの呼び出しの解決・どのオーバーロードが選ばれるか・私的メンバーによる隠蔽・エラー）は
 * F の宣言にも依存する。そこで、ある型が「変わった型」になったら、その部分型もすべて（推移的に）「変わった型」に
 * する。F が変われば E と D も変わった型になり、D を使う側を解析し直す。親が jar の型なら、その親（別の jar の型の
 * ことも）は H 行に無いので、I 行に jar の型の推移的な親型を載せ（{@link BindingNames}。Q86）、I 行が変わった jar に
 * 触れていたファイルの型を変わった型にする（上の「宣言の連鎖」）。
 *
 * <p>部分型は、H 行の親型の列から作る部分型の索引（{@link StaleTypes#register}）で引く。索引には、旧キャッシュの
 * すべてのブロック（有効なものも無効なものも）と、今回解析した・引き継いだブロックの H 行を足す（親型の関係は
 * 前回と今回の和で見る。多すぎても解析し直すファイルが増えるだけ）。索引に関係を足すときも、型を変わった型に
 * するときも、そのたびに部分型へ広げるので、「変わった型」はどの時点でも索引について部分型で閉じていて、H 行を
 * 読む順・ファイルを解析し直す順に依らない。パス3・パス4 の周回は「変わった型」が増えなくなるまで続く。
 *
 * <p>親のメソッドの本体・コメントだけを変えても、部分型を使う側は解析し直す（形が変わったかは見ない）。
 * 以前は型の形（継承したものを含むメンバーの署名）の指紋を I 行に持ち、形が変わったときだけ連鎖させていたが、
 * 何を形に入れるか（私的メンバー・{@code java.*} の親型の上のメンバー）で取りこぼしが続いたので、やめた
 * （docs/cache-unification-qa.md の Q77）。形が拾っていた「選ばれなかったオーバーロードの引数の型」は、呼ぶ側の I 行に
 * 呼び出しの候補の引数の型として載せる（{@link BindingNames#noteCandidates}。Q78）。
 *
 * <h2>新しい型（前回は無かった型は、参照していた側の I 行に載らない。依存を 1 段で済ませられない場合の 3 つ目）</h2>
 * 前回どのブロックも宣言していなかった型（ソースを足した・消したファイルを戻した・既存のファイルに型を
 * 足した）は、次の 2 通りで、I 行に触れずに他のファイルの解決結果を変える。
 * <ul>
 *   <li>無い型の名前（{@code Foo.run()}）には JDT がバインディングを返さないので、参照した側の依存には
 *       何も残らない。そのファイルは F 行のエラー数か U 行の BINDING_FAILED として型解決に失敗していて、
 *       I 行の 2 列目にエラーの引数に現れた名前（{@code Foo}・{@code org.missing.Lib}）を持つ。型解決に失敗していた
 *       ブロックのうち、その名前が変わった型（新しい型を含む）に当たるもの（{@link StaleTypes#matchesChangedType}）を
 *       周回ごとに解析し直す。無名・ローカルの型は名前で参照できないので当たらない（docs/cache-unification-qa.md の
 *       Q42）。新しい型に限らないのは、見えなかった型を public にしたとき（{@code The type p.Hidden is not visible}）も
 *       同じ形で失敗が解けるため（Q53）。名前の頭の部分（{@code org.missing.pkg.Type} の {@code org.missing}）が、できた・
 *       無くなったパッケージ（前回と今回の H 行のパッケージと、ファイルの置き場所のフォルダのパッケージを比べる。JDT は
 *       パッケージがあるかをフォルダで決める）か変わった jar のパッケージ（とその頭の部分）に当たる
 *       ものも解析し直す。パッケージができる・無くなると、JDT がどこまでをパッケージとして読むか（エラーと回復した型の
 *       名前）が変わるため（Q80）。オンデマンド import（{@code import a.*}）のパッケージができた・無くなったときも、
 *       その import を持つブロックを解析し直す（{@code a} が下のパッケージだけでできていると、最後の下のパッケージが
 *       無くなったときに import のエラーになる）</li>
 *   <li>同じパッケージに足したトップレベルの型は、オンデマンド import（{@code import q.*}）と {@code java.lang} の型を
 *       隠す（JLS 6.4.1）。自分のパッケージは I 行に無いので、新しい型のパッケージと同じパッケージの
 *       ブロックのうち、I 行に同じ単純名の型があるものを解析し直す（Q43）</li>
 *   <li>パッケージと同じ名前の型（パッケージ {@code a.b} があるのに足したパッケージ {@code a} のクラス {@code b}）は、
 *       {@code a.b.C} の解決を変える（JLS 6.5.2・7.1）。I 行の型の名前の頭の部分が変わった型に当たるブロックも
 *       解析し直す（{@link StaleTypes#touches}。Q87）。逆に、型 {@code a.b} があるところにパッケージ {@code a.b} が
 *       できた・無くなった（jar のパッケージが変わった）ときは、型のファイルの「パッケージと衝突する」エラーが出る・消える。
 *       このエラーはバッチに依らない（JDT はフォルダと jar でパッケージがあるかを決める）ので、親のパッケージ {@code a}
 *       のブロックを解析し直す（{@link StaleTypes#collidesWithChangedPackage}）</li>
 * </ul>
 * 「前回は無かった」は、パス1 を読み終えたときの「変わった型」（無効になったブロックが宣言していた型と、
 * その部分型）に無いことで見る。有効なブロックの型でも部分型でなければそこに無いので、別のファイルに同じ名前の型が
 * 重複しているときも新しい型とみなす（解析し直すファイルが増えるだけで、結果は変わらない）。
 *
 * 依存 jar の変更も同じ仕組みで扱う。jar の中の型は解析し直せない（ソースが無い）ので、
 * 「その jar のパッケージの型を参照しているファイル」を再解析の対象にする。
 * 型ではなくパッケージで見るのは、jar の版を差し替えたときに旧版にだけあった型を
 * 新しい jar からは知れないためで、L 行にパッケージ一覧を残すのは jar が削除された後にも
 * 影響範囲を知るため（{@link LibraryDiff}）。jar の型が自分と同じパッケージにできたときは、新しい型と同じく
 * オンデマンド import と {@code java.lang} の型を隠しうるので、そのパッケージのブロックも見る（Q54）。
 * jar が追加・変更されたときだけは、型解決に失敗していたファイル（F 行のエラー数、U 行の BINDING_FAILED）も
 * 解析し直す（パス1。無い型の名前は I 行に残らないので、パッケージでは当たらない）。削除と並び替えだけなら
 * 解決できる型は増えず、失敗していた型解決が成功に変わる理由にならないので、失敗していたファイルは解析し直さない
 * （{@link LibraryDiff#anyAddedOrChanged}）。型のメンバーを持ち込む import（{@code import static org.lib.K.*}・
 * {@code import org.lib.Outer.*}）は I 行に {@code org.lib.K.*} と載るので、頭の部分が変わった jar のパッケージかでも当てる。
 * jar の無名パッケージのクラスは {@link LibraryFact#UNNAMED_PACKAGE} というパッケージとして扱い、点の無い型の名前が当たる。
 *
 * <h2>解析に失敗したファイル（中身の分からないパッケージ）</h2>
 * 解析に失敗したファイル（JDT のスタックが溢れた。Q62）は事実を書けないが、その型は JDT がソースパスから読むので、
 * ほかのファイルの解決には効いている。どの型を宣言しているかも分からないので、そのファイルの置き場所のパッケージを
 * 「中身の分からないパッケージ」にして、変わった jar のパッケージと同じ決まりで、そのパッケージの型を使うファイルを
 * 解析し直す（{@link StaleTypes#addOpaque}）。そのファイルには印のブロック（F 行と空の I 行。内容ハッシュは空）を書き、
 * 次の実行も必ず解析し直す（失敗が続くあいだは、実行のたびにそのパッケージの型を使うファイルも解析し直す）。消したときは、
 * 型を宣言していなかった無効なブロックとして、パス1 で同じくそのパッケージを中身の分からないパッケージにする。
 */
public final class CacheUpdater {

    private final ProjectLayout layout;
    private final Config config;
    /**
     * 相対パス -> 今のソースの内容ハッシュ。同じファイルを 2 度読まないように覚える
     * （パス1 の再利用の判定、T 行のソース一覧の指紋、F 行に書く値で使う）
     */
    private final Map<String, String> hashes = new HashMap<>();
    /**
     * 相対パス -> 旧キャッシュの自分の宣言の指紋（I 行の 3 列目。有効なブロックのぶん）。
     * パス4で解析し直した結果と突き合わせて、宣言と定数の値が変わったかだけを見る（「宣言の連鎖」）。
     * ファイルごとに 16 文字のハッシュ1つなので、ヒープに載せても軽い
     */
    private final Map<String, String> oldDeclarations = new HashMap<>();
    /**
     * 検査値が合わなかったブロックのうち、そのファイルを今回解析し直すものの数
     * （旧キャッシュと引き継ぎの一時ファイルの合計。ログに 1 回だけ出す）。
     * 今のソースに無いファイルのブロックと、旧キャッシュを丸ごと捨てたときのブロックは数えない
     * （「それらのファイルは解析し直す」という案内が事実と食い違うため）
     */
    private int damagedBlocks;
    /**
     * 解析したファイルのうち、解析のあいだに中身が変わったもの（解析の前と後で内容ハッシュが違う）。
     * F 行の内容ハッシュを空にして書いてある（次の実行で必ず解析し直す）。クラスの説明「実行中に書き換えられたソース」
     */
    private final Set<String> changedDuringRun = new HashSet<>();
    /**
     * この実行で解析したファイル（ブロックを書いたものも、解析に失敗したものも）。解析のあいだにソースが消えた・増えた
     * ときは、これらのブロックの内容ハッシュを空にする（{@link #invalidateChangedDuringRun}。JDT がその一時的な状態を
     * 読んだかもしれないのは、この実行で解析したファイルだけ）。文字列は {@code live} のものを共有する
     */
    private final Set<String> parsedThisRun = new HashSet<>();
    /** パス0 で今回のクラスパスを走査した結果。書き終えたときに見かけを見比べる（{@link #invalidateClasspathChangedDuringRun}） */
    private LibraryDiff libraries;
    /** 同じコンパイル単位の名前のファイル（同じクラスが 2 つのソースフォルダにある）。run の最初に作る */
    private SameUnitFiles units = SameUnitFiles.NONE;
    /**
     * 旧キャッシュのヘッダ行のソースフォルダ（{@link CacheFormat#foldersOf}）。パス0 で読む。
     * 消えたファイルのコンパイル単位の名前を求めるのに使う（{@link SameUnitFiles#pairedWithDeleted}）
     */
    private List<String> oldFolders = List.of();
    /** 旧キャッシュのヘッダ行と今回のヘッダ行で、ソースフォルダの一覧だけが違うか。パス0 で読む */
    private boolean foldersChanged;
    /**
     * 旧キャッシュ。パス0 で開き、パス1（全体を読む）・パス3（索引が無いときの I 行）・パス5（書き写し）まで
     * このチャネルだけで読む（名前で開き直さない）。無い・使わない設定なら null
     */
    private FileChannel oldChannel;
    /** 開いたときの旧キャッシュの大きさ。読むたびに見比べ、違えば止める（{@link #checkOldCacheUnchanged}） */
    private long oldSize;

    /**
     * 検査用の差し込み口（test/incremental の EditDuringRunCheck だけが使う。本番では null）。
     * バッチを JDT に渡す直前に、そのバッチのファイルを渡す。解析の途中でソースを書き換える場面を、
     * 時間に頼らずに作るため
     */
    static volatile Consumer<List<SourceFile>> beforeBatchForTest;

    public CacheUpdater(ProjectLayout layout, Config config) {
        this.layout = layout;
        this.config = config;
    }

    public CachePhaseResult run() throws IOException {
        CachePhaseResult result = new CachePhaseResult();

        List<Path> javaFiles = layout.listJavaFiles();
        Log.info(Messages.format("analysis.javaFileCount", javaFiles.size()));

        // 相対パス -> ソースファイルの実体情報（これだけはヒープに載せる）。
        // あわせて、解析を始めたときの更新時刻を live の並びの順に覚える。実行中にソースが書き換えられたかを
        // 見るためだけに使い、キャッシュには書かない（クラスの説明「実行中に書き換えられたソース」）
        Map<String, SourceFile> live = new LinkedHashMap<>();
        long[] startTimes = new long[javaFiles.size()];
        for (Path f : javaFiles) {
            String rel = layout.relativeOf(f);
            BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
            startTimes[live.size()] = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            live.put(rel, new SourceFile(f, rel, attrs.size()));
        }
        units = SameUnitFiles.of(live, layout);

        Path parent = config.cacheFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        deleteLegacyFiles();
        TempFiles.deleteLeftovers(config.cacheFile);
        Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");
        // 前回が途中で終わっていれば、その一時ファイルを退避して「パースの使い回し」に使う
        // （決まった名前だが、同じキャッシュのフォルダを使う実行は錠で 1 つずつにしてある。jche.cache.CacheLock）
        Path partialCache = takeOverPartial(config.cacheFile, tmpCache);

        Progress progress = new Progress(Messages.get("analysis.progress.parse"), javaFiles.size(),
                CallEdgeExtractor.BATCH_SIZE);
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);

        // 旧キャッシュはここで 1 回だけ開き、パス0 からパス5 まで同じチャネルで読む（クラスの説明「旧キャッシュの読み方」）。
        // 差し替える（最後の move）前に必ず閉じる（Windows は開いているファイルを置き換えられない）
        openOldCache();
        boolean written;
        try {
            written = update(live, tmpCache, partialCache, progress, extractor, result);
        } finally {
            closeOldCache();
        }
        if (!written) {
            return result;   // 何も変わっていない。旧キャッシュをそのまま残した
        }
        progress.finish();

        invalidateChangedDuringRun(tmpCache, live, startTimes);
        invalidateClasspathChangedDuringRun(tmpCache);
        Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    /**
     * パス0〜パス5。新キャッシュを一時ファイルに書く。
     *
     * @return 一時ファイルに書いたか。何も変わっていなくて旧キャッシュをそのまま残すなら false
     */
    private boolean update(Map<String, SourceFile> live, Path tmpCache, Path partialCache, Progress progress,
                           CallEdgeExtractor extractor, CachePhaseResult result) throws IOException {
        // --- パス0: 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせる ---
        List<LibraryFact> oldLibraries = (oldChannel != null) ? readOldLibraries() : null;
        LibraryDiff libraries = LibraryDiff.compute(layout.classpathArray(),
                (oldLibraries != null) ? oldLibraries : List.of(), layout.projectRoot);
        this.libraries = libraries;
        boolean oldCacheUsable = (oldLibraries != null) && environmentStillAccepted(extractor, libraries);
        if (oldCacheUsable && libraries.any()) {
            Log.info(Messages.format("analysis.libraryChanged", libraries));
        }

        // パス1 が書き、パス3 が読む依存の索引（有効なブロックの I 行）。何があっても最後に消す
        try (DepsIndex deps = new DepsIndex(config.cacheFile)) {
            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes(libraries.changedPackages);
            // 今のソースの置き場所のフォルダのパッケージ（前回のぶんはパス1 で旧キャッシュの F 行のパスから数える）。
            // JDT はパッケージがあるかをフォルダで決めるので、型の無いファイルだけのパッケージも数える（StaleTypes#endOfSources）
            for (SourceFile f : live.values()) {
                stale.packageNow(StaleTypes.packageOfUnit(layout.unitNameOf(f.path())));
            }
            Set<String> libraryAffected = new HashSet<>();   // 型解決に失敗していて、jar の追加で変わりうるファイル
            OldCache old = oldCacheUsable
                    ? scanOldCache(live, valid, stale, libraries.anyAddedOrChanged(), libraryAffected, deps)
                    : null;
            if (old == null) {
                // 途中で切れている・読めないキャッシュ。中途半端に再利用すると呼び出しが静かに欠けるので、
                // 丸ごと捨てて全件解析し直す（ヘッダが違ったときと同じ扱い）
                valid.clear();
                libraryAffected.clear();
                oldDeclarations.clear();
            } else {
                // 同じ名前のファイルの組の片方が消えた（フォルダを外した場合も）。残ったほうのブロックには、組が同じ
                // バッチにいたときの「型が重複している」エラーが残っているので、再利用せずに解析し直す（SameUnitFiles）
                valid.removeAll(SameUnitFiles.pairedWithDeleted(old.deleted, oldFolders, live, layout));
            }
            // ここまでに集めた型（無効になったブロックが宣言していた型）が「前回宣言されていた型」。
            // パス2 で宣言された型がこれに無ければ「新しい型」（クラスの説明「新しい型」）
            stale.endOfOldCache();
            // ソース一覧の指紋（T行）。ハッシュはパス1 と共通で、1ファイル 1 回しか読まない（hashOf）
            String sources = fingerprintOf(live);
            // ヘッダ・L 行・T 行。この並びは形式で決まっている
            List<String> head = headLinesOf(libraries.current, sources);

            // --- パス2 で解析するファイル ---
            List<SourceFile> changed = new ArrayList<>();
            List<SourceFile> unresolvedBefore = new ArrayList<>();
            for (Map.Entry<String, SourceFile> en : live.entrySet()) {
                if (libraryAffected.contains(en.getKey())) {
                    unresolvedBefore.add(en.getValue());
                } else if (!valid.contains(en.getKey())) {
                    changed.add(en.getValue());
                }
            }
            // 同じクラスが 2 つのソースフォルダにあるとき、その組は必ず一緒に解析する（SameUnitFiles）
            units.pullInto(changed, unresolvedBefore, valid, live);

            if (canKeepAsIs(old, changed, unresolvedBefore, stale, head)) {
                // 何も変わっていない。書き直しても同じバイト列になるので、旧キャッシュをそのまま残す
                if (partialCache != null) {
                    salvageFrom(partialCache, changed, live, sources, libraries.current, null, stale, result);
                }
                deletePartial(partialCache);
                keepAsIs(old, result);
                progress.step(result.reused);
                progress.finish();
                return false;
            }

            FileChannel outChannel = FileChannel.open(tmpCache, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // 閉じると outChannel も閉じる（Channels.newOutputStream の close は元のチャネルを閉じる）
            try (BufferedWriter cacheOut = new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(outChannel), StandardCharsets.UTF_8.newEncoder()))) {
                for (String line : head) {
                    writeLine(cacheOut, line);
                }
                BlockWriter writer = new BlockWriter(cacheOut, result, progress, this::hashOf, oldDeclarations,
                        changedDuringRun, parsedThisRun,
                        f -> StaleTypes.packageOfUnit(layout.unitNameOf(f.path())));

                // --- パス2: 変更・追加されたファイルを解析 ---
                writer.stale = stale;
                // ファイル自身が変わっているので、宣言する型は無条件に「変わった型」へ（改名・追加に備える）
                writer.cascade = Cascade.ALWAYS;
                if (partialCache != null) {
                    changed = salvageFrom(partialCache, changed, live, sources, libraries.current, cacheOut,
                            stale, result);
                    writer.skipped(result.salvaged);
                }
                deletePartial(partialCache);
                if (damagedBlocks > 0) {
                    // 検査値の合わないブロックは再利用も引き継ぎもしていない（そのファイルは解析し直す）
                    Log.info(Messages.format("analysis.cache.damagedBlocks", damagedBlocks));
                }
                analyzeInBatches(extractor, changed, writer);
                writer.countAs = Reason.BY_LIBRARY;
                analyzeInBatches(extractor, unresolvedBefore, writer);

                // --- パス3・パス4: 依存で無効になったファイルを解析し直す（宣言が変わると連鎖するので不動点まで） ---
                writer.cascade = Cascade.WHEN_DECLARATIONS_CHANGED;
                if (old != null && !valid.isEmpty()) {
                    reanalyzeDependents(extractor, writer, live, valid, stale, deps, old);

                    // --- パス5: 最後まで有効だったブロックを書き写す ---
                    copyValidBlocks(old, valid, outChannel, cacheOut, result);
                    writer.skipped(result.reused);
                }

                // 最終行。次回、ここまで書き終えたキャッシュかどうか（とブロックの数）を見分けるための印。
                // 解析に失敗したファイルの印のブロック（BlockWriter#failed）も数える
                writeLine(cacheOut, CacheFormat.trailerFor(result.parsed + result.reused + result.salvaged
                        + writer.failedBlocks()));
            }
        }
        return true;
    }

    /**
     * 旧キャッシュを書いたときからソースフォルダか依存 jar が変わったなら、JDT が今回のクラスパス・ソースパスを
     * 受け付けるかを確かめる（{@link CallEdgeExtractor#environmentProblem}）。受け付けなければ false（旧キャッシュを使わない）。
     *
     * <p>受け付けられないと、どのファイルも解析できない（全件解析ではすべて失敗し、ブロックが 1 つも無い）。
     * それでも旧キャッシュのブロックを使うと、差分更新だけが前の設定の結果を出す（docs/cache-unification-qa.md の Q73）。
     * どちらも変わっていなければ確かめない。ヘッダ行（JDK・ソースレベル・文字コード・ソースフォルダ）と L 行が同じなら
     * JDT に渡すものも同じで、旧キャッシュにブロックがあるならそれを書いた実行で受け付けられている（受け付けられなかった
     * 実行はブロックを書かない）。何も変わっていない実行で JDT を読み込まずに済ませるため（確かめると 0.3 秒ほどかかる）
     */
    private boolean environmentStillAccepted(CallEdgeExtractor extractor, LibraryDiff libraries) {
        if (!foldersChanged && !libraries.any()) {
            return true;
        }
        String problem = extractor.environmentProblem();
        if (problem == null) {
            return true;
        }
        Log.info(Messages.format("analysis.cache.environmentRejected", problem));
        return false;
    }

    /**
     * キャッシュの先頭の行（ヘッダ行・依存 jar の L 行・ソース一覧の T 行）。書くときも、旧キャッシュと比べるときも使う。
     * T 行の最後の列は先頭の行の検査値（ヘッダ行・L 行と、検査値の列を空にした T 行の CRC32。{@link BlockChecksum}）
     */
    private List<String> headLinesOf(List<LibraryFact> libraries, String sources) {
        List<String> lines = new ArrayList<>(libraries.size() + 2);
        BlockChecksum checksum = new BlockChecksum();
        String header = expectedHeader();
        lines.add(header);
        checksum.add(header);
        for (LibraryFact l : libraries) {
            String row = l.toRow();
            lines.add(row);
            checksum.add(row);
        }
        checksum.addWithoutLastColumn(CacheFormat.sourcesRow(sources, ""));
        lines.add(CacheFormat.sourcesRow(sources, checksum.hex()));
        return lines;
    }

    /**
     * 今回のヘッダ行（キャッシュの鍵）。形式の版・ソースレベル・文字コード・JDK・JDT の版に加えて、
     * ソースフォルダの並び（project.root からの相対パス）も入れる。JDT は同じ名前の型が 2 つのソースフォルダに
     * あると先に並ぶ方で解決するので、並びが変われば同じソースでも解決先が変わる（{@link CacheFormat#headerFor}）。
     * フォルダを足した・外しただけなら旧キャッシュを使い続ける（{@link CacheFormat#headerReusable}）
     */
    private String expectedHeader() {
        List<String> folders = new ArrayList<>(layout.sourceFolders.size());
        for (Path sf : layout.sourceFolders) {
            folders.add(layout.relativeOf(sf));
        }
        return CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding, JdtVersion.current(), folders);
    }

    /**
     * 旧キャッシュを書き直さずにそのまま残してよいか。書き直した結果が旧キャッシュとバイト単位で同じになるときだけ
     * true にする（残しても書き直しても、次の実行とフェーズ2 が読むものは同じ）。
     * <ul>
     *   <li>解析するファイルが無い（変更・追加・jar の追加で解析し直すファイルが無い。依存で解析し直すファイルも
     *       無い＝「変わった型」も「変わった jar のパッケージ」も無い）</li>
     *   <li>旧キャッシュのどのブロックも有効（消えたファイル・壊れたブロックが無い）</li>
     *   <li>先頭の行（ヘッダ・L 行の中身と並び・T 行）が今回書くものと同じ</li>
     *   <li>旧キャッシュが書き手の書くとおりの形（空行・CRLF が無く、最終行の Z 行が 1 つだけ）。
     *       そうでなければ、書き直すと形が整うぶんだけバイト列が変わる</li>
     * </ul>
     * 中断した前回の実行から引き継ぐものも無い（解析するファイルが無ければ引き継ぎもしない）。
     */
    private boolean canKeepAsIs(OldCache old, List<SourceFile> changed, List<SourceFile> unresolvedBefore,
                                StaleTypes stale, List<String> head) throws IOException {
        if (old == null || !old.allKept || !old.writtenAsIs || !changed.isEmpty() || !unresolvedBefore.isEmpty()
                || !stale.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : head) {
            sb.append(line).append('\n');
        }
        byte[] expected = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (old.blocksStart != expected.length) {
            return false;
        }
        checkOldCacheUnchanged();
        ByteBuffer actual = ByteBuffer.allocate(expected.length);
        while (actual.hasRemaining() && oldChannel.read(actual, actual.position()) > 0) {
            // 旧キャッシュの先頭を、パス1 と同じチャネルから読む
        }
        return !actual.hasRemaining() && Arrays.equals(actual.array(), expected);
    }

    /**
     * 旧キャッシュをそのまま残すときの集計。書き直したとき（パス5 で全ブロックを書き写したとき）と同じ数え方にする
     */
    private static void keepAsIs(OldCache old, CachePhaseResult result) {
        Log.info(Messages.get("analysis.cache.unchanged"));
        for (int i = 0; i < old.size; i++) {
            result.reused++;
            // 前の実行でエラーだったファイルは、今回もエラーのままである（数えないと警告が消える）
            result.countErrors(old.paths[i], old.errors[i], old.syntaxErrors[i]);
            result.unresolved += old.unresolved[i];
        }
    }

    /**
     * 以前の形式（キャッシュが 2 ファイルだった版）が残した {@code dataflow-cache.tsv} と、
     * その一時ファイル・退避ファイルを消す。
     *
     * 今の形式は読まないので、残しておくとディスクを食うだけでなく、GitHub Actions のキャッシュのように
     * フォルダごと保存する使い方では保存のたびに持ち越される。利用者が対処することは無いので、
     * 消したことは {@code Log.info} で知らせるだけにする（{@code warnings.txt} には載せない）
     */
    private void deleteLegacyFiles() {
        Path legacy = config.cacheFile.resolveSibling(Config.LEGACY_DATAFLOW_CACHE_FILE_NAME);
        for (Path p : List.of(legacy, legacy.resolveSibling(legacy.getFileName() + ".tmp"),
                legacy.resolveSibling(legacy.getFileName() + ".partial"))) {
            try {
                if (Files.deleteIfExists(p)) {
                    Log.info(Messages.format("analysis.cache.legacyDeleted", p.getFileName()));
                }
            } catch (IOException e) {
                Log.info(Messages.format("analysis.cache.legacyNotDeleted", p.getFileName(), e));
            }
        }
    }

    private static List<SourceFile> filesOf(List<String> relativePaths, Map<String, SourceFile> live) {
        List<SourceFile> files = new ArrayList<>();
        for (String rel : relativePaths) {
            SourceFile file = live.get(rel);
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * BATCH_SIZE 件ずつまとめてパースし、1ファイル分ずつ writer に渡す。
     * 同じコンパイル単位の名前のファイル（同じクラスが 2 つのソースフォルダにある）は、同じバッチに並べる
     * （{@link SameUnitFiles#batches}。全件解析でも差分更新でも同じ組で JDT に渡すため）
     */
    private void analyzeInBatches(CallEdgeExtractor extractor, List<SourceFile> files,
                                  BlockWriter writer) throws IOException {
        int done = 0;
        for (List<SourceFile> batch : units.batches(files, CallEdgeExtractor.BATCH_SIZE)) {
            // 中止の確認はバッチの切れ目で行う。ここで抜けてもキャッシュはテンポラリのままなので壊れない
            RunControl.checkCancelled();
            RunControl.progress(Messages.get("analysis.progress.parse"), done, files.size());
            Consumer<List<SourceFile>> hook = beforeBatchForTest;
            if (hook != null) {
                hook.accept(batch);
            }
            extractor.analyzeBatch(batch, writer);
            done += batch.size();
        }
    }

    /**
     * ファイルを解析し直す理由（集計の内訳）。{@link StaleTypes#touches} の判定結果と
     * {@link BlockWriter#countAs} の両方で使う（以前は別々の定数で同じ意味を表していた）
     */
    private enum Reason {
        /** 変わった型にも jar にも触れていない（再解析しない）。自分が変わった（または新規）ファイルの集計にも使う */
        UNTOUCHED,
        /** 依存する型（ソース）が変わった */
        BY_SOURCE,
        /** 依存 jar が変わった */
        BY_LIBRARY
    }

    /**
     * 解析し直したファイルが宣言する型を「変わった型」に加えるかどうか。
     *
     * 加えると、その型を参照しているファイルがもう一周で再解析に回る（{@link CacheUpdater} の
     * 「宣言の連鎖」）。無条件に加えると、解析し直すたびに参照元へ芋づる式に広がって
     * 差分更新の意味が無くなるので、必要な場合だけに絞る。
     */
    private enum Cascade {
        /** 常に加える（パス2。ファイル自身が変わっているので、型の改名・追加がありうる） */
        ALWAYS,
        /**
         * 自分の宣言の指紋（宣言の鍵と修飾子・定数の値。I 行の 3 列目）が旧キャッシュと違うときと、
         * 旧キャッシュでそのファイルが変わった jar のパッケージに触れていたとき（選ばれた理由に依らない）に加える（パス4）。
         *
         * ほかのファイルの事実は、このファイルの宣言（メソッドの引数と戻り値の型・フィールドの型・親型・修飾子）と
         * 定数の値（使う側に焼き込まれる）に依る。中身の変わっていないファイルでも、宣言に書いた名前の解決先
         * （同じパッケージに足した型による隠蔽）や参照した定数の値が変わると、それらが変わる。変わっていなければ、
         * 使っている側の事実は変わらないので連鎖させない（ここが「案3」との違いで、不要な再解析が増えないように
         * している）。変わった jar に触れていたファイルは、指紋に入らない継承したもの（jar の親の親のメンバー）が
         * 変わりうるので、常に加える（docs/cache-unification-qa.md の Q84・Q89）。sealed な型かアノテーション型を
         * 宣言するファイルも、使う側の事実が指紋に入らないもの（許した部分型の宣言・メタ注釈の解決先）に依るので、
         * 常に加える（{@link FileAnalysis#cascadesWhenReanalysed}）
         */
        WHEN_DECLARATIONS_CHANGED
    }

    /**
     * 解析結果を受け取って即座にキャッシュへ書き出し、件数と進捗を数える。
     * 1ファイル分だけをヒープに載せ、書き出したら即破棄する。
     */
    private static final class BlockWriter implements CallEdgeExtractor.Sink {
        private final BufferedWriter cacheOut;
        private final CachePhaseResult result;
        private final Progress progress;
        /** 解析したファイルの内容ハッシュを求める（F行に書くため） */
        private final Function<SourceFile, String> hasher;
        /** 相対パス -> 旧キャッシュの自分の宣言の指紋（{@link Cascade#WHEN_DECLARATIONS_CHANGED} の判定用） */
        private final Map<String, String> oldDeclarations;
        /** 解析のあいだに中身が変わったファイル（{@link CacheUpdater#changedDuringRun}）を積む先 */
        private final Set<String> changedDuringRun;
        /** この実行で解析したファイル（{@link CacheUpdater#parsedThisRun}）を積む先 */
        private final Set<String> parsedThisRun;
        /** ファイルの置き場所のフォルダのパッケージ（{@link StaleTypes#packageOfUnit}。解析に失敗したファイルに使う） */
        private final Function<SourceFile, String> packageOfFile;
        /** 最後にブロックを書いたファイル（受け手の途中で失敗したときに、印のブロックを重ねて書かないため） */
        private String lastWritten;
        /** 書いた印のブロックの数（{@link #failed}。Z 行のブロック数に足す） */
        private long failedBlocks;
        /** 「変わった型」の集合。非nullのときだけ {@link #cascade} に従って型を加える（連鎖の判定にも使う） */
        StaleTypes stale;
        /** 解析したファイルが宣言する型を「変わった型」に加える条件 */
        Cascade cascade = Cascade.ALWAYS;
        /** 解析した理由。集計の内訳にだけ使う（UNTOUCHED は「自分が変わった・新規」。連鎖の判定には使わない） */
        Reason countAs = Reason.UNTOUCHED;
        /**
         * 旧キャッシュの I 行か解決できなかった名前が、変わった jar のパッケージか中身の分からないパッケージ
         * （{@link StaleTypes#addOpaque}。解析に失敗したファイルの）に触れていたファイル（相対パス）。
         * どの理由で選ばれたか（{@link #countAs}）に依らず、解析し直したら宣言する型を「変わった型」に加える（Q84）。
         * 理由で決めると、同じファイルがソースの変化にも触れていたときに連鎖を落とす（Q89）
         */
        final Set<String> jarDriven = new HashSet<>();
        private long done;

        BlockWriter(BufferedWriter cacheOut, CachePhaseResult result, Progress progress,
                    Function<SourceFile, String> hasher, Map<String, String> oldDeclarations,
                    Set<String> changedDuringRun, Set<String> parsedThisRun,
                    Function<SourceFile, String> packageOfFile) {
            this.cacheOut = cacheOut;
            this.result = result;
            this.progress = progress;
            this.hasher = hasher;
            this.oldDeclarations = oldDeclarations;
            this.changedDuringRun = changedDuringRun;
            this.parsedThisRun = parsedThisRun;
            this.packageOfFile = packageOfFile;
        }

        /** 書いた印のブロックの数 */
        long failedBlocks() {
            return failedBlocks;
        }

        /**
         * 解析したファイルが宣言する型を「変わった型」に加えるか。
         * パス4 では、旧キャッシュでそのファイルが変わった jar のパッケージに触れていたとき（{@link #jarDriven}）と、
         * sealed な型かアノテーション型を宣言しているとき（{@link FileAnalysis#cascadesWhenReanalysed}）と、
         * 自分の宣言の指紋が旧キャッシュと違うときだけ加える。
         * このファイルの旧キャッシュと解析結果だけで決まり、ほかのファイルをどの順に解析し直したか
         * （「変わった型」がその時点で何を含むか）にも、どの理由で選ばれたかにも依らない
         */
        private boolean shouldCascade(SourceFile file, FileAnalysis fa) {
            return cascade == Cascade.ALWAYS || jarDriven.contains(file.relativePath())
                    || fa.cascadesWhenReanalysed
                    || !oldDeclarations.getOrDefault(file.relativePath(), "").equals(declarationsDigestOf(fa));
        }

        @Override
        public void accept(SourceFile file, FileAnalysis fa) throws IOException {
            parsedThisRun.add(file.relativePath());
            fa.hash = hashAfterParse(file);
            // writeBlock はブロックをメモリ上で組み終えてから書くので、途中で例外が出ても書きかけは残らない
            // （例外は呼び出し元がこのファイルの失敗として数える）。書けたら直後に数え、Z 行の数と揃える
            writeBlock(fa, cacheOut);
            lastWritten = file.relativePath();
            result.parsed++;
            result.unresolved += fa.unresolvedCount();
            result.countErrors(file.relativePath(), fa.errors, fa.syntaxErrors);
            if (fa.syntaxErrors > 0) {
                // 本体を読めていないので、このファイルの呼び出しは出力に出ない。黙って落とさない
                Warnings.warn(Warnings.Topic.BUILD,
                        Messages.format("analysis.syntaxError", file.relativePath(), fa.syntaxErrors));
            }
            countReason();
            if (stale != null) {
                // 今回の親型の関係も部分型の索引に足す（親型の連鎖）。パッケージは今のソースのもの
                for (TypeFact t : fa.types) {
                    stale.register(t);
                    stale.packageNow(t.pkg());
                }
            }
            if (stale != null && shouldCascade(file, fa)) {
                for (TypeFact t : fa.types) {
                    if (cascade == Cascade.ALWAYS && countAs == Reason.UNTOUCHED) {
                        // ファイル自身が変わった・増えた。新しい型かも見る（jar の追加で解析し直すファイルは
                        // 中身が変わっていないので、宣言する型も前回と同じ）
                        stale.addDeclared(t.typeFqn(), t.pkg());
                    } else {
                        stale.add(t.typeFqn(), t.pkg());
                    }
                }
            }
            progress.step(++done);
        }

        /**
         * F 行に書く内容ハッシュ。解析の前（パス1 で求めたもの）と、JDT が読み終えた今とで中身が同じなら、そのハッシュ。
         * 違えば空文字（JDT が読んだのが前と後のどちらの中身か分からない。空のハッシュはどの中身とも一致しないので、
         * 次の実行で必ず解析し直す）。前のハッシュのまま書くと、解析のあいだに書き換えて元に戻したファイルが、
         * 書き換えた中身の事実のまま再利用され続ける（クラスの説明「実行中に書き換えられたソース」）
         */
        private String hashAfterParse(SourceFile file) {
            String before = hasher.apply(file);
            String now;
            try {
                now = FileHash.of(file.path());
            } catch (IOException e) {
                now = "";
            }
            if (!before.isEmpty() && before.equals(now)) {
                return before;
            }
            if (!before.isEmpty()) {
                changedDuringRun.add(file.relativePath());
            }
            return "";
        }

        /**
         * 解析に失敗したファイル（JDT のスタックが溢れた・受け手の失敗。docs/cache-unification-qa.md の Q62・Q69）。
         *
         * <p>事実は書けないが、そのファイルの型は JDT がソースパスから読むので、ほかのファイルの解決には効いている。
         * 以前は何も残さなかったので、そのファイルを書き換えても・消しても、その型を使うファイルを解析し直さなかった。
         * <ul>
         *   <li>印のブロック（F 行と空の I 行だけ。内容ハッシュは空で、次の実行でも必ず解析し直す）を書く。消したときに、旧キャッシュに
         *       ファイルがあったことが分かる（パス1 は型を宣言しないブロックの置き場所のパッケージを中身の分からない
         *       パッケージにする。{@link CacheUpdater#finishOldBlock}）。ブロックを書いたあとの受け手の失敗なら書かない</li>
         *   <li>置き場所のパッケージを中身の分からないパッケージにする（{@link StaleTypes#addOpaque}）。宣言する型は
         *       分からないので、変わった jar のパッケージと同じ決まりで、そのパッケージの型を使うファイルを解析し直す。
         *       失敗が続くあいだは実行のたびに解析し直す（安全側の費用）</li>
         * </ul>
         */
        @Override
        public void failed(SourceFile file, Exception error) {
            result.failed++;
            countReason();
            Warnings.warn(Warnings.Topic.INCOMPLETE,
                    Messages.format("analysis.fileFailed", file.relativePath(), error.getMessage()));
            String rel = file.relativePath();
            parsedThisRun.add(rel);
            if (!rel.equals(lastWritten)) {
                // F 行と空の I 行（F 行の直後は必ず I 行。CacheFormat）。検査値は writeBlock と同じ求め方
                String deps = CacheFormat.joinRow("I", "", "", "");
                BlockChecksum checksum = new BlockChecksum();
                checksum.addWithoutLastColumn(CacheFormat.fileRow(rel, file.size(), 0, "", 0, 0, ""));
                checksum.add(deps);
                try {
                    writeLine(cacheOut, CacheFormat.fileRow(rel, file.size(), 0, "", 0, 0, checksum.hex()));
                    writeLine(cacheOut, deps);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);   // 書けなければ実行ごと失敗させる（解析の呼び出し元が戻す）
                }
                failedBlocks++;
            }
            if (stale != null && !declaresNoType(rel)) {
                stale.addOpaque(packageOfFile.apply(file));
            }
            progress.step(++done);
        }

        private void countReason() {
            if (countAs == Reason.BY_SOURCE) {
                result.dependents++;
            } else if (countAs == Reason.BY_LIBRARY) {
                result.libraryDependents++;
            }
        }

        /** 再利用したぶんを進捗に足す */
        void skipped(long count) {
            done += count;
            progress.step(done);
        }
    }

    /**
     * 変更・削除されたファイルが宣言していた型とそのパッケージ、
     * および追加・変更・削除された jar のパッケージ
     */
    private static final class StaleTypes {

        private final Set<String> types = new HashSet<>();
        private final Set<String> packages = new HashSet<>();
        private final Set<String> libraryPackages;
        /**
         * パス1 の終わりの {@link #types}（無効になったブロックが宣言していた型）。パス2 で宣言された型が
         * これに無ければ新しい型。{@link #endOfOldCache} を呼ぶまでは null（新しい型を数えない）
         */
        private Set<String> declaredBefore;
        /** パッケージ -> そこに新しく宣言されたトップレベルの型の単純名（同じパッケージの名前の隠蔽を見る） */
        private final Map<String, Set<String>> newTopLevel = new HashMap<>();
        /**
         * 変わった型（{@link #types}。新しい型を含む）の単純名（FQN の最後の点より後。無名・ローカルの型は
         * {@code Main$1} の形なので、エラーの引数の名前には当たらない）。型解決に失敗していたブロックのうち、
         * 解決できなかった名前（I 行）のどれかの区切りがこれに当たるものを解析し直す（{@link #matchesChangedType}）。
         * 新しい型に限らないのは、見えなかった型を public にした（{@code The type vp.Hidden is not visible}）ときの
         * ように、前からあった型の変更でも失敗が解けるため（docs/cache-unification-qa.md の Q53）
         */
        private final Set<String> changedSimpleNames = new HashSet<>();
        /**
         * 変わった型の FQN の、点で区切った頭の部分（{@code org}・{@code org.acme}・{@code org.acme.New}）。
         * 解決できなかった名前がこれに当たるブロック（{@code import org cannot be resolved}）も解析し直す
         */
        private final Set<String> changedPrefixes = new HashSet<>();
        /**
         * 部分型の索引（親型の連鎖）。親型 -> それを親に持つ型（H 行の親型の列。子が 1 つなら {@code String}、
         * 2 つ以上なら {@code List<String>}）。旧キャッシュのすべてのブロックと、今回解析した・引き継いだブロックの
         * H 行から作る（{@link #register}）。差分更新のときだけ作り、型 1 つあたり文字列 1 つと参照 1 つで持つ
         */
        private final Map<String, Object> subtypes = new HashMap<>();
        /**
         * ソースのパッケージとその頭の部分。前回（旧キャッシュのすべてのブロックの H 行）と今回（有効なブロックと、今回
         * 解析した・引き継いだブロックの H 行）。片方にだけあるものは、できた・無くなったパッケージ。変わった jar の
         * パッケージとその頭の部分（{@link #libraryPrefixes}）も、できた・無くなったかもしれないものとして扱う。
         * パッケージがあるかどうかで、JDT が解決できなかった名前（{@code org.missing.pkg.Type}）をどこまでパッケージ
         * として読むか（エラーと、回復した型の名前 {@code org.missing}・{@code org.missing.pkg}）が変わる
         * （docs/cache-unification-qa.md の Q80）
         */
        private final Set<String> packagesBefore = new HashSet<>();
        private final Set<String> packagesNow = new HashSet<>();
        private final Set<String> libraryPrefixes = new HashSet<>();
        /**
         * 中身（宣言する型）の分からないソースのパッケージ。解析に失敗したファイル（{@link BlockWriter#failed}）と、
         * 無効になった・消えたブロックのうち型を 1 つも宣言していなかったもの（解析に失敗したファイルの印のブロックを含む）の
         * パッケージ（置き場所のフォルダから求める。{@link #packageOfUnit}）。どの型が増えた・減った・変わったか分からないので、
         * 変わった jar のパッケージと同じ決まりで扱う（{@link #touchesPackages}）。無名パッケージは
         * {@link LibraryFact#UNNAMED_PACKAGE}
         */
        private final Set<String> opaquePackages = new HashSet<>();
        private final Set<String> opaquePrefixes = new HashSet<>();
        /** {@link #newTopLevel} の単純名をパッケージを問わず集めたもの（自分のパッケージの分からないブロックに使う） */
        private final Set<String> newTopLevelNames = new HashSet<>();
        /**
         * できた・無くなったパッケージ（{@link #packagesBefore} と {@link #packagesNow} の片方にだけあるもの）。
         * 今のパッケージがそろうパス3 の最初に {@link #endOfSources} で決める。それまでは空
         */
        private final Set<String> changedSourcePackages = new HashSet<>();
        /**
         * {@link #changedSourcePackages} と {@link #libraryPrefixes} の親のパッケージ（{@link #collidesWithChangedPackage}）。
         * 前者は {@link #endOfSources} で、後者は作るときに決める
         */
        private final Set<String> parentsOfChangedSource = new HashSet<>();
        private final Set<String> parentsOfChangedLibrary = new HashSet<>();

        StaleTypes(Set<String> libraryPackages) {
            this.libraryPackages = new HashSet<>(libraryPackages);
            for (String p : libraryPackages) {
                addPrefixes(p, libraryPrefixes);
            }
            addParents(libraryPrefixes, parentsOfChangedLibrary);
        }

        /**
         * パス2 を終えた（今のソースのパッケージがそろった）。できた・無くなったパッケージを決める。
         * パス3・パス4 で解析し直すファイルは中身が変わっていないので、パッケージも変わらない
         */
        void endOfSources() {
            changedSourcePackages.clear();
            for (String p : packagesBefore) {
                if (!packagesNow.contains(p)) {
                    changedSourcePackages.add(p);
                }
            }
            for (String p : packagesNow) {
                if (!packagesBefore.contains(p)) {
                    changedSourcePackages.add(p);
                }
            }
            parentsOfChangedSource.clear();
            addParents(changedSourcePackages, parentsOfChangedSource);
        }

        /**
         * 中身の分からないパッケージを加える（{@link #opaquePackages}）。{@code pkg} は空文字なら無名パッケージ。
         * {@code null}（パッケージを求められない）なら無名パッケージとみなす（無名パッケージの型の名前には点が無いので、
         * 当たるのは点の無い名前だけ。多すぎても解析し直すファイルが増えるだけ）
         */
        void addOpaque(String pkg) {
            String p = (pkg == null || pkg.isEmpty()) ? LibraryFact.UNNAMED_PACKAGE : pkg;
            if (opaquePackages.add(p)) {
                addPrefixes(p, opaquePrefixes);
            }
        }

        /** 前回のソースにあった型のパッケージ（旧キャッシュの H 行） */
        void packageBefore(String pkg) {
            if (pkg != null && !pkg.isEmpty()) {
                addPrefixes(pkg, packagesBefore);
            }
        }

        /** 今のソースにある型のパッケージ（有効なブロックと、今回解析した・引き継いだブロックの H 行） */
        void packageNow(String pkg) {
            if (pkg != null && !pkg.isEmpty()) {
                addPrefixes(pkg, packagesNow);
            }
        }

        void add(String typeFqn, String pkg) {
            mark(typeFqn);
            packages.add(pkg == null ? "" : pkg);
        }

        /**
         * 型を「変わった型」にし、索引にあるその部分型も（推移的に）変わった型にする。部分型は、親から継承したものが
         * 変わっているので、それを使う側の事実も変わりうる（クラスの説明「親型の連鎖」）
         */
        private void mark(String typeFqn) {
            ArrayDeque<String> work = new ArrayDeque<>();
            work.add(typeFqn);
            while (!work.isEmpty()) {
                String t = work.poll();
                if (!types.add(t)) {
                    continue;
                }
                changedSimpleNames.add(t.substring(t.lastIndexOf('.') + 1));
                addPrefixes(t, changedPrefixes);
                Object children = subtypes.get(t);
                if (children instanceof String child) {
                    work.add(child);
                } else if (children != null) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) children;
                    work.addAll(list);
                }
            }
        }

        /**
         * H 行 1 つの親型の関係を部分型の索引に足す。親がすでに変わった型なら、この型も（その部分型も）変わった型に
         * する（親が変わった jar のパッケージの型なら、この型を宣言するファイルは親を I 行に持つので jar の変化で
         * 解析し直し、そのとき変わった型になる。{@link Cascade#WHEN_DECLARATIONS_CHANGED}）。索引に足すのと
         * 変わった型を広げるのをどちらの向きでも行うので、変わった型はいつでも「索引に載った関係について部分型で
         * 閉じている」。H 行をどの順に読んでも、型をどの順に変わった型にしても、最後に同じ集合になる
         */
        void register(TypeFact t) {
            String child = t.typeFqn();
            for (String parent : t.superTypes()) {
                Object known = subtypes.get(parent);
                if (known == null) {
                    subtypes.put(parent, child);
                } else if (known instanceof String one) {
                    List<String> list = new ArrayList<>(2);
                    list.add(one);
                    list.add(child);
                    subtypes.put(parent, list);
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) known;
                    list.add(child);
                }
                if (types.contains(parent)) {
                    mark(child);
                }
            }
        }

        /** FQN の点で区切った頭の部分（{@code org}・{@code org.acme}）と FQN そのものを足す */
        private static void addPrefixes(String typeFqn, Set<String> out) {
            for (int dot = typeFqn.indexOf('.'); dot > 0; dot = typeFqn.indexOf('.', dot + 1)) {
                out.add(typeFqn.substring(0, dot));
            }
            out.add(typeFqn);
        }

        /** パス1 を読み終えた。ここまでの型を「前回宣言されていた型」として固定する */
        void endOfOldCache() {
            declaredBefore = new HashSet<>(types);
        }

        /**
         * 変わった（または新しい）ファイルが宣言する型を加える。前回宣言されていなかった型なら、
         * 新しい型としても覚える（クラスの説明「新しい型」）
         */
        void addDeclared(String typeFqn, String pkg) {
            add(typeFqn, pkg);
            if (declaredBefore == null || declaredBefore.contains(typeFqn)) {
                return;
            }
            String p = (pkg == null) ? "" : pkg;
            String rest = p.isEmpty() ? typeFqn
                    : typeFqn.startsWith(p + ".") ? typeFqn.substring(p.length() + 1) : null;
            if (rest != null && !rest.isEmpty() && rest.indexOf('.') < 0) {
                // トップレベルの型だけ。入れ子の型（Main.Inner）は外側の型（前回もあった）を通してしか名前にならない
                newTopLevel.computeIfAbsent(p, k -> new HashSet<>()).add(rest);
                newTopLevelNames.add(rest);
            }
        }

        /**
         * 型解決に失敗していたブロックの、解決できなかった名前（I 行。{@link FileAnalysis#unresolvedNames}）が
         * 変わった型（新しい型を含む）に当たるか。名前の区切りのどれかが変わった型の単純名か（{@code Foo}・
         * {@code q.Foo}・{@code Foo.Inner}）、名前が変わった型の FQN の頭の部分か（{@code org}・{@code org.acme}）、
         * 名前の頭の部分（名前そのものは除く）が、できた・無くなったパッケージか（{@link #packagesBefore}。
         * {@code org.missing.pkg.Type} と、前回は無かったパッケージの {@code org.missing.Foo}）。
         * {@link CacheFormat#ANY_NAME}（名前を拾えなかった）は何にでも当たる
         *
         * @param namesCsv 名前のカンマ区切り
         */
        boolean matchesChangedType(String namesCsv) {
            if (changedSimpleNames.isEmpty() && libraryPackages.isEmpty() && changedSourcePackages.isEmpty()) {
                return false;   // どのファイルも変わっていない（パッケージもできていない・無くなっていない）
            }
            if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
                return true;
            }
            for (String name : namesCsv.split(",")) {
                if (changedPrefixes.contains(name) || hasSegment(name, changedSimpleNames)
                        || underChangedPackage(name)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 名前の頭の部分（{@code org}・{@code org.missing}。名前そのものは除く）のどれかが、できた・無くなったパッケージか
         * （前回と今回の片方にだけある。変わった jar のパッケージとその頭の部分も）。今回のパッケージはパス2 を終えれば
         * そろう（有効なブロックはパス1、変わったファイルはパス2 で足す）ので、パス3 から使う
         */
        private boolean underChangedPackage(String name) {
            for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                String p = name.substring(0, dot);
                if (libraryPrefixes.contains(p) || packagesBefore.contains(p) != packagesNow.contains(p)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 「変わった型」も「変わった jar のパッケージ」も「中身の分からないパッケージ」も「できた・無くなったパッケージ」も
         * 無い（＝どのブロックも再解析に回らない）。できた・無くなったパッケージは {@link #endOfSources} のあとで数える
         */
        boolean isEmpty() {
            return types.isEmpty() && libraryPackages.isEmpty() && opaquePackages.isEmpty()
                    && changedSourcePackages.isEmpty();
        }

        /**
         * これまでに加えた型と中身の分からないパッケージの数。連鎖の打ち切りに使う。
         * 1周しても増えていなければ、もう一周しても同じ結果になる（どちらも増える一方で減らない）
         */
        int mark() {
            return types.size() + opaquePackages.size();
        }

        /**
         * 自分のパッケージ（{@code ownPackage}）の中に、できた・無くなったパッケージ（とその頭の部分）か、変わった jar の
         * パッケージ（とその頭の部分）があるか。パッケージ {@code a} のトップレベルの型 {@code b} は、パッケージ
         * {@code a.b} と衝突する（JLS 7.1。JDT は型のファイルに「collides with a package」のエラーを出す）。JDT がパッケージが
         * あるかをソースフォルダのフォルダと jar から決めるので、このエラーは型のファイルをどのバッチで解析しても同じで、
         * 型のファイルを解析し直せば全件解析と同じになる（docs/cache-unification-qa.md の Q87 の逆向き）。
         * どの型が衝突するかは見ず、親のパッケージのファイルをすべて解析し直す（多すぎても解析し直すファイルが増えるだけ）。
         * 無名パッケージ（{@code ownPackage} が空文字）は見ない。JDT は無名パッケージの型とトップレベルのパッケージの衝突を
         * 報告しない
         *
         * @return 当たらなければ {@link Reason#UNTOUCHED}。ソースのパッケージに当たれば {@link Reason#BY_SOURCE}、
         *         jar のパッケージだけに当たれば {@link Reason#BY_LIBRARY}
         */
        Reason collidesWithChangedPackage(String ownPackage) {
            if (ownPackage == null || ownPackage.isEmpty()) {
                return Reason.UNTOUCHED;
            }
            if (parentsOfChangedSource.contains(ownPackage)) {
                return Reason.BY_SOURCE;
            }
            return parentsOfChangedLibrary.contains(ownPackage) ? Reason.BY_LIBRARY : Reason.UNTOUCHED;
        }

        /** パッケージの親（最後の点より前）を集める。点の無いもの（親が無名パッケージ）は入れない */
        private static void addParents(Set<String> packages, Set<String> out) {
            for (String p : packages) {
                int dot = p.lastIndexOf('.');
                if (dot > 0) {
                    out.add(p.substring(0, dot));
                }
            }
        }

        /**
         * I行（依存する型のカンマ区切り）が、変わった型または変わった jar のパッケージに触れているか。
         * "pkg.*"（オンデマンド import）は、そのパッケージの型が1つでも変わっていれば触れているとみなす。
         * ソースの変更に触れていればそちらを理由として返す（集計の内訳のため）。
         *
         * <p>自分のパッケージ（{@code ownPackage}）に新しいトップレベルの型ができていて、I 行のどれかの型名に
         * その単純名が含まれていれば（{@code q.Helper} と新しい {@code p.Helper}、{@code java.lang.Math} と
         * 新しい {@code p.Math}）、名前が隠されて別の型に解決されうるので触れているとみなす（JLS 6.4.1）
         *
         * <p>自分のパッケージに変わった jar の型があれば（同じパッケージを jar とソースに分けて置く）、jar の型も
         * 同じパッケージの型として、オンデマンド import と {@code java.lang} の型を隠しうる（JLS 6.4.1）。jar のどの
         * 型が増えたかは分からないので、I 行にオンデマンド import か {@code java.lang} の型があれば触れているとみなす
         * （docs/cache-unification-qa.md の Q54）
         *
         * <p>I 行の型の名前の頭の部分が変わった型なら（パッケージ {@code a.b} と同じ名前の型 {@code a.b} を足した）、
         * {@code a.b.C} の解決が変わるので触れているとみなす（{@link #underChangedType}）
         *
         * <p>オンデマンド import（{@code p.*}）は、パッケージ {@code p} ができた・無くなったときも触れているとみなす。
         * JDT はパッケージ {@code a} がその下のパッケージ（{@code a.b}）だけでできていても {@code import a.*} を解決するので、
         * 最後の下のパッケージが無くなると import のエラーが出る（{@link #endOfSources}）
         *
         * <p>中身の分からないパッケージ（{@link #opaquePackages}。解析に失敗したファイルのパッケージ）には、変わった jar の
         * パッケージと同じ決まりで触れる（{@link #touchesPackages}）。理由はソースの変化にする
         *
         * @param ownPackage そのブロックが宣言する型のパッケージ。型を 1 つも宣言していない（{@code package-info.java}）
         *                   なら null で、そのときはどのパッケージのブロックでもありうるとみなす
         */
        Reason touches(String depsCsv, String ownPackage) {
            if (depsCsv.isEmpty() || isEmpty()) {
                return Reason.UNTOUCHED;
            }
            if (touchesSource(depsCsv, ownPackage) || touchesOpaque(depsCsv, ownPackage)) {
                return Reason.BY_SOURCE;
            }
            return touchesLibrary(depsCsv, ownPackage) ? Reason.BY_LIBRARY : Reason.UNTOUCHED;
        }

        /** I 行がソースの変化（変わった型・パッケージ・名前を隠す新しい型）に触れるか。{@link #touches} のソースの側 */
        private boolean touchesSource(String depsCsv, String ownPackage) {
            // 自分のパッケージが分からない（型を宣言していない）ブロックは、どのパッケージの新しい型にも隠されうるとみなす
            Set<String> shadowing = (ownPackage == null)
                    ? (newTopLevelNames.isEmpty() ? null : newTopLevelNames)
                    : newTopLevel.get(ownPackage);
            for (String d : depsCsv.split(",")) {
                if (d.isEmpty()) {
                    continue;
                }
                if (shadowing != null && !d.endsWith(".*") && hasSegment(d, shadowing)) {
                    return true;
                }
                boolean onDemand = d.endsWith(".*");
                String p = onDemand ? d.substring(0, d.length() - 2) : d;
                // 型の名前（か頭の部分）ができた・無くなったパッケージと同じなら、型とパッケージの衝突（JLS 7.1）で
                // 解決が変わりうる（collidesWithChangedPackage の、使う側）
                if (types.contains(p) || underChangedType(p) || changedSourcePackages.contains(p)
                        || underPackages(p, changedSourcePackages) || (onDemand && packages.contains(p))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * I 行が変わった jar のパッケージに触れるか。{@link #touches} の jar の側で、ソースの変化に触れるかとは
         * 別に見る（解析し直したときに連鎖させるか。{@link BlockWriter#jarDriven}）
         */
        boolean touchesLibrary(String depsCsv, String ownPackage) {
            return touchesPackages(depsCsv, ownPackage, libraryPackages, libraryPrefixes);
        }

        /**
         * I 行が中身の分からないパッケージ（{@link #opaquePackages}）に触れるか。触れていたブロックは、変わった jar に
         * 触れていたブロックと同じく、解析し直したら宣言する型を変わった型にする（{@link BlockWriter#jarDriven}）
         */
        boolean touchesOpaque(String depsCsv, String ownPackage) {
            return touchesPackages(depsCsv, ownPackage, opaquePackages, opaquePrefixes);
        }

        /**
         * I 行が、中身の分からない変化のあったパッケージ（{@code pkgs}。変わった jar のパッケージか、解析に失敗したファイルの
         * パッケージ）に触れるか。どの型が増えた・減った・変わったか分からないので、次のどれかなら触れているとみなす。
         * <ul>
         *   <li>型（{@code a.b.C}・{@code a.b.C.Inner}）の頭の部分がそのパッケージ。点の無い型（無名パッケージの型）は、
         *       無名パッケージ（{@link LibraryFact#UNNAMED_PACKAGE}）がそこにあるとき。型の名前そのものがそのパッケージか
         *       その頭の部分（{@code prefixes}）のときも（型とパッケージの衝突。JLS 7.1）</li>
         *   <li>オンデマンド import（{@code p.*}）の {@code p} が、そのパッケージかその頭の部分（{@code prefixes}）か、
         *       頭の部分がそのパッケージ（{@code import static org.lib.K.*}・{@code import org.lib.Outer.*} の型
         *       {@code org.lib.K} のパッケージ {@code org.lib}。型のメンバーを持ち込む import も {@code 名前.*} の形で I 行に
         *       載るので、名前そのものではなく頭の部分で当てる）</li>
         *   <li>自分のパッケージがそこにある（同じパッケージを jar とソースに分けて置く・無名パッケージ。自分のパッケージが
         *       分からなければあるとみなす）。I 行に何かあれば当たる。そのパッケージの型が同じパッケージの型として、
         *       オンデマンド import・{@code java.lang} の型（JLS 6.4.1。docs/cache-unification-qa.md の Q54）だけでなく、
         *       完全修飾名の頭の区切り（{@code a.b.C} の {@code a}。JLS 6.5.2 で型が先に選ばれる）も隠しうる。以前は
         *       オンデマンド import と {@code java.lang} の型だけを見ていて、I 行にそれらの無いファイル（インターフェース）が
         *       {@code a.b.C.k()} を書いていると、同じパッケージにできた型 {@code a} による隠蔽を見落とした</li>
         * </ul>
         */
        private static boolean touchesPackages(String depsCsv, String ownPackage, Set<String> pkgs,
                                               Set<String> prefixes) {
            if (depsCsv.isEmpty() || pkgs.isEmpty()) {
                return false;
            }
            if (ownPackage == null || pkgs.contains(ownPackage.isEmpty() ? LibraryFact.UNNAMED_PACKAGE : ownPackage)) {
                return true;
            }
            for (String d : depsCsv.split(",")) {
                if (d.isEmpty()) {
                    continue;
                }
                if (d.endsWith(".*")) {
                    String p = d.substring(0, d.length() - 2);
                    if (prefixes.contains(p) || underPackages(p, pkgs)) {
                        return true;
                    }
                } else if (underPackages(d, pkgs) || prefixes.contains(d)
                        || (d.indexOf('.') < 0 && pkgs.contains(LibraryFact.UNNAMED_PACKAGE))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 解決できなかった名前（I 行の 2 列目）が、変わった jar のパッケージ（とその頭の部分）に当たりうるか。
         * 名前を拾えなかった（空・{@link CacheFormat#ANY_NAME}）ときは、jar が変わっていれば当たるとみなす
         */
        boolean namesUnderLibrary(String namesCsv) {
            if (libraryPackages.isEmpty()) {
                return false;
            }
            if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
                return true;
            }
            for (String name : namesCsv.split(",")) {
                for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                    if (libraryPrefixes.contains(name.substring(0, dot))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 型解決に失敗していたブロックの、解決できなかった名前（I 行の 2 列目）が、中身の分からないパッケージの型に
         * なりうるか。自分のパッケージがそこにある（分からなければあるとみなす）ときは、名前に依らず当たる。名前の頭の
         * 区切り（{@code Base2}・{@code Base2.Inner} の {@code Base2}）が同じパッケージの型の単純名でありうるため
         * （点の有無では分けない。以前は点の無い名前だけを見ていて、同じパッケージの失敗するファイルに足した型の入れ子の型
         * {@code Base2.Inner} を落とした）。そうでなければ、名前の頭の部分がそのパッケージ（とその頭の部分）のとき。
         * 名前を拾えなかった（空・{@link CacheFormat#ANY_NAME}）ときは当たるとみなす。オンデマンド import で単純名を
         * 持ち込むブロックは、I 行の {@code p.*} で当たる（{@link #touchesOpaque}）
         */
        boolean namesUnderOpaque(String namesCsv, String ownPackage) {
            if (opaquePackages.isEmpty()) {
                return false;
            }
            if (namesCsv == null || namesCsv.isEmpty() || namesCsv.equals(CacheFormat.ANY_NAME)) {
                return true;
            }
            if (ownPackage == null
                    || opaquePackages.contains(ownPackage.isEmpty() ? LibraryFact.UNNAMED_PACKAGE : ownPackage)) {
                return true;
            }
            for (String name : namesCsv.split(",")) {
                if (underPackages(name, opaquePrefixes)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 名前の頭の部分（{@code a.b.C} の {@code a}・{@code a.b}。名前そのものは除く）のどれかが変わった型か。
         * パッケージ {@code a.b} と同じ名前の型 {@code a.b}（パッケージ {@code a} のクラス {@code b}）を足す・消すと、
         * {@code a.b.C} と書いたファイルの解決が変わる（JLS 6.5.2・7.1。docs/cache-unification-qa.md の Q87）。
         * 入れ子の型（{@code p.Outer.Inner} と変わった {@code p.Outer}）も当たる。入れ子の型はたいてい外側の型と
         * 一緒に変わった型になっている（同じファイル）ので、これで増えるのは外側の型だけが部分型の索引で変わった型に
         * なったときだけである（多すぎても解析し直すファイルが増えるだけ）
         */
        private boolean underChangedType(String name) {
            for (int dot = name.indexOf('.'); dot > 0; dot = name.indexOf('.', dot + 1)) {
                if (types.contains(name.substring(0, dot))) {
                    return true;
                }
            }
            return false;
        }

        /** 型名を "." で区切ったどれかが、names に含まれるか */
        private static boolean hasSegment(String typeFqn, Set<String> names) {
            int from = 0;
            while (from <= typeFqn.length()) {
                int dot = typeFqn.indexOf('.', from);
                int end = (dot < 0) ? typeFqn.length() : dot;
                if (names.contains(typeFqn.substring(from, end))) {
                    return true;
                }
                if (dot < 0) {
                    return false;
                }
                from = dot + 1;
            }
            return false;
        }

        /**
         * 名前の頭の部分（名前そのものは除く）のどれかが {@code pkgs} にあるか。型名なら、そのパッケージが
         * {@code pkgs} にあるか（名前だけではどこまでがパッケージか（内部クラスかどうか）分からないので、
         * "." で区切った前方部分を全部試す）
         */
        private static boolean underPackages(String name, Set<String> pkgs) {
            if (pkgs.isEmpty()) {
                return false;
            }
            for (int i = name.indexOf('.'); i > 0; i = name.indexOf('.', i + 1)) {
                if (pkgs.contains(name.substring(0, i))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * ソースフォルダから見たコンパイル単位の名前（{@code a/b/C.java}。{@link ProjectLayout#unitNameOf}）から、
         * 置き場所のフォルダのパッケージ（{@code a.b}）を求める。フォルダの直下なら空文字（無名パッケージ）
         */
        static String packageOfUnit(String unitName) {
            int slash = unitName.lastIndexOf('/');
            return (slash <= 0) ? "" : unitName.substring(0, slash).replace('/', '.');
        }
    }

    /**
     * ブロックのF行が、今のソースと一致しているか。サイズと内容ハッシュの両方で見る。
     * 更新時刻は見ない（クラスの説明「同一性」のとおり）。
     *
     * <p>サイズを先に見るのは、違えば中身も違うと分かり、ファイルを読まずに済むため。
     * ハッシュが記録されていない F 行（読み取りに失敗したなど）は不一致とみなす。
     */
    private boolean isValidBlock(String[] f, Map<String, SourceFile> live) {
        if (f.length < 3) {
            return false;
        }
        SourceFile st = live.get(f[1]);
        if (st == null) {
            return false;
        }
        try {
            if (st.size() != Long.parseLong(f[2])) {
                return false;   // サイズ違いは中身も違う。ハッシュを取るまでもない
            }
        } catch (NumberFormatException ignore) {
            return false;   // 壊れたF行 -> このブロックは破棄し、後で再解析される
        }
        String recorded = CacheFormat.columnAt(f, 4);
        return !recorded.isEmpty() && recorded.equals(hashOf(st));
    }

    /** 旧キャッシュを開く（無い・使わない設定なら開かない。開けなければ使わない） */
    private void openOldCache() {
        if (!config.cacheEnabled || !Files.isRegularFile(config.cacheFile)) {
            return;
        }
        try {
            oldChannel = FileChannel.open(config.cacheFile, StandardOpenOption.READ);
            oldSize = oldChannel.size();
        } catch (IOException | RuntimeException e) {
            // 読めない・開けないキャッシュ。全件解析し直せば済むので、解析ごと失敗させない
            Log.warn(Messages.format("analysis.cache.unreadable", e));
            closeOldCache();
        }
    }

    private void closeOldCache() {
        if (oldChannel == null) {
            return;
        }
        try {
            oldChannel.close();
        } catch (IOException e) {
            Log.info(Messages.format("analysis.cache.unreadable", e));
        }
        oldChannel = null;
    }

    /**
     * 旧キャッシュが開いたときの大きさのままか。違えば、読んでいるあいだに書き換えられた（同じキャッシュのフォルダを
     * 錠を持たずに使うもの＝錠を知らない古い版のこのツールや、手での書き換え）。パス1 で覚えた位置が当てにならないので、
     * 例外にして解析を止める（パス1 の中なら、読めないキャッシュとして丸ごと捨てる）
     */
    private void checkOldCacheUnchanged() throws IOException {
        if (oldChannel == null || oldChannel.size() != oldSize) {
            throw new IOException(Messages.format("analysis.cache.changedWhileReading", config.cacheFile));
        }
    }

    /**
     * 解析のあいだにソースが書き換えられていたら、そのファイルのブロックの内容ハッシュを空にする
     * （次の実行で必ず解析し直し、そのファイルの型を「変わった型」として依存するファイルも解析し直す）。
     *
     * <p>JDT は解析するファイルのほかに、参照している型のソースもソースパスから読む。解析のあいだに書き換えられた
     * ファイル X を読んだ別のファイルの事実は、X の書き換え後の中身に基づく。X の F 行に解析を始めたときのハッシュが
     * 残っていると、X を元に戻したあとの実行で X は「変わっていない」と見なされ、書き換え後の中身で解析したファイルが
     * 古いまま再利用され続ける。更新時刻（と大きさ）で見るのは実行のあいだの見張りだけで、キャッシュには書かない
     * （同一性は内容ハッシュで見る。クラスの説明「同一性」）。解析したファイルは JDT が読んだ直後のハッシュでも
     * 確かめてある（{@link BlockWriter#hashAfterParse}）
     *
     * <p>ソースが消えた・増えたときも同じ。解析のあいだに消えた（読めない）ファイルは、そのブロックの内容ハッシュを
     * 空にする（あとで同じ中身のまま戻されると、空にしなければ「変わっていない」と読んでしまう）。一覧を作ったときに
     * 無かったファイルが今ある（解析のあいだに足された）かも、ソースフォルダを歩き直して見る。どちらかがあれば、
     * この実行で解析したファイル（{@link #parsedThisRun}）のブロックの内容ハッシュもすべて空にする。JDT はそれらを
     * 解析するときに、消えていた・一時的にあったファイルをソースパスから読んだ（読めなかった）かもしれず、そのファイルが
     * 戻された・消されたあとは、どのブロックにもその痕跡が残らないため。再利用したブロックは前の実行で作ったもので、
     * この実行の一時的な状態を読んでいない
     *
     * @param startTimes 解析を始めたときの更新時刻（{@code live} の並びの順）
     */
    private void invalidateChangedDuringRun(Path tmpCache, Map<String, SourceFile> live, long[] startTimes)
            throws IOException {
        Set<String> blank = new HashSet<>();
        boolean sourceSetChanged = false;
        int i = 0;
        for (SourceFile f : live.values()) {
            long started = startTimes[i++];
            if (changedDuringRun.contains(f.relativePath())) {
                continue;   // 内容ハッシュを空にして書いてある
            }
            try {
                BasicFileAttributes attrs = Files.readAttributes(f.path(), BasicFileAttributes.class);
                if (attrs.size() == f.size() && attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS) == started) {
                    continue;
                }
            } catch (IOException e) {
                // 消された（あとで戻されるかもしれない）。消えたままなら、次の実行でブロックごと落ちる
                sourceSetChanged = true;
            }
            blank.add(f.relativePath());
        }
        if (!sourceSetChanged) {
            sourceSetChanged = sourcesAdded(live);
        }
        if (sourceSetChanged) {
            blank.addAll(parsedThisRun);
        }
        if (blank.isEmpty() && changedDuringRun.isEmpty()) {
            return;
        }
        // 解析したファイルを空にするときは、中身が変わったファイル（changedDuringRun）と重なりうる。同じファイルを 2 回数えない
        List<String> all = new ArrayList<>(new TreeSet<>(changedDuringRun));
        for (String rel : new TreeSet<>(blank)) {
            if (!changedDuringRun.contains(rel)) {
                all.add(rel);
            }
        }
        Log.info(Messages.format("analysis.cache.changedDuringRun", all.size(),
                String.join(", ", all.subList(0, Math.min(all.size(), 5)))));
        if (!blank.isEmpty()) {
            blankHashes(tmpCache, blank);
        }
    }

    /**
     * 解析を始めたときのソースの一覧（{@code live}）に無いファイルが、今のソースフォルダにあるか（解析のあいだに
     * 足された）。歩けなければ、あるとみなす（安全側。この実行で解析したファイルを次の実行で解析し直すだけ）
     */
    private boolean sourcesAdded(Map<String, SourceFile> live) {
        try {
            for (Path p : layout.listJavaFiles()) {
                if (!live.containsKey(layout.relativeOf(p))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /**
     * 解析のあいだに依存 jar・クラスフォルダが書き換えられていた（パス0 で走査したときと見かけが違う）か、JDK が
     * このプロセスの中で前の中身の目次を見せ続けていた（{@link LibraryDiff#staleInProcess}）なら、この実行で解析した
     * ファイルのブロックの内容ハッシュを空にする。次の実行はそれらを解析し直し、宣言する型を「変わった型」にして
     * 依存するファイルも解析し直す（クラスの説明「実行中に書き換えられたソース」）。
     *
     * <p>どのファイルが書き換えた中身を読んだかは分からない（JDT はどのバッチでもクラスパスを読みうる）ので、解析した
     * ものすべてを空にする。再利用したブロック（と引き継いだブロック）は、パス0 の指紋と同じ中身に対して前の実行が
     * 作ったものなので残す。書き換えたまま戻さなかったときは、次の実行で指紋が変わるので、ふつうの jar の変化としても
     * 解析し直す
     */
    private void invalidateClasspathChangedDuringRun(Path tmpCache) throws IOException {
        if (libraries == null || parsedThisRun.isEmpty()) {
            return;
        }
        List<Path> changed = libraries.changedSinceScan();
        if (changed.isEmpty() && !libraries.staleInProcess) {
            return;
        }
        if (!changed.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Path p : changed.subList(0, Math.min(changed.size(), 5))) {
                names.add(p.toString());
            }
            Log.info(Messages.format("analysis.cache.classpathChangedDuringRun", changed.size(),
                    String.join(", ", names), parsedThisRun.size()));
        }
        blankHashes(tmpCache, parsedThisRun);
    }

    /**
     * 書き終えた一時ファイルのうち、{@code paths} のブロックの F 行の内容ハッシュを空にする（検査値も求め直す）。
     * めったに通らない経路なので、一時ファイルを行として読み直して別の一時ファイルに書き、差し替える
     * （ヒープに載せるのは書き直すブロック 1 つ分だけ）
     */
    private void blankHashes(Path tmpCache, Set<String> paths) throws IOException {
        Path rewritten = TempFiles.create(config.cacheFile, TempFiles.REWRITE);
        try {
            try (CacheReader in = CacheReader.open(tmpCache);
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(rewritten),
                         StandardCharsets.UTF_8.newEncoder()))) {
                writeLine(out, in.header());
                String[] fileRow = null;          // 書き直すブロックの F 行（書き直さないなら null）
                List<String> body = new ArrayList<>();
                while (in.next()) {
                    char rowType = in.rowType();
                    if (rowType == CacheFormat.ROW_FILE || rowType == CacheFormat.ROW_END) {
                        flushBlanked(fileRow, body, out);
                        fileRow = null;
                        body.clear();
                        if (rowType == CacheFormat.ROW_FILE && paths.contains(in.filePath())) {
                            fileRow = in.columns();
                            continue;
                        }
                    }
                    if (fileRow != null) {
                        body.add(in.line());
                    } else {
                        writeLine(out, in.line());
                    }
                }
                flushBlanked(fileRow, body, out);
            }
            Files.move(rewritten, tmpCache, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            TempFiles.delete(rewritten);
        }
    }

    /** 内容ハッシュを空にした F 行と、求め直した検査値でブロックを書く（{@code fileRow} が null なら何もしない） */
    private static void flushBlanked(String[] fileRow, List<String> body, BufferedWriter out) throws IOException {
        if (fileRow == null) {
            return;
        }
        String[] f = Arrays.copyOf(fileRow, Math.max(fileRow.length, 8));
        for (int i = 0; i < f.length; i++) {
            if (f[i] == null) {
                f[i] = "";
            }
        }
        f[4] = "";   // 内容ハッシュ
        f[7] = "";   // 検査値（下で求め直す）
        BlockChecksum checksum = new BlockChecksum();
        checksum.addWithoutLastColumn(CacheFormat.joinRow(f));
        for (String line : body) {
            checksum.add(line);
        }
        f[7] = checksum.hex();
        writeLine(out, CacheFormat.joinRow(f));
        for (String line : body) {
            writeLine(out, line);
        }
    }

    /** 今のソースの内容ハッシュ（計算は 1 ファイル 1 回）。読めなければ空文字 */
    private String hashOf(SourceFile file) {
        String h = hashes.get(file.relativePath());
        if (h == null) {
            try {
                h = FileHash.of(file.path());
            } catch (IOException e) {
                Log.warn(Messages.format("analysis.hashFailed", file.relativePath(), e));
                h = "";
            }
            hashes.put(file.relativePath(), h);
        }
        return h;
    }

    /**
     * パス0。旧キャッシュのヘッダを検証し、L 行（解析時の依存 jar）を読む。
     *
     * 次のどれかに当たれば null（旧キャッシュを使わずに全件再解析）。
     * <pre>
     *   - キャッシュが無い
     *   - ヘッダの形式（版・ソースレベル・文字コード・JDK・JDT）が違う
     *   - 読めない
     * </pre>
     * ここで読むのはヘッダと L 行・T 行だけ（最初の F 行で打ち切る）。<b>キャッシュ全体は走らない。</b>
     * 毎回の実行で通る経路なので、ここでフルスキャンすると差分更新の利点をそのぶん削ってしまう。
     * 途中で切れている・壊れているかは、全体を読むパス1（{@link #scanOldCache}）が見る
     */
    private List<LibraryFact> readOldLibraries() {
        try (CacheReader in = CacheReader.open(oldChannel)) {
            CacheHead head = headOf(in, true);
            if (head == null) {
                // 形式が変わった場合のほか、source.level・ソースの文字コード・実行 JDK・JDT・ソースフォルダの並びが
                // 変わった場合もここで破棄する。言語バージョン・文字コード・ブートクラスパスが違えば
                // 同じソースでも解析結果が変わるため、F 行の同一性が一致していても再利用してはいけない
                Log.info(Messages.get("analysis.cache.incompatible"));
                return null;
            }
            foldersChanged = !in.headerMatches(expectedHeader());
            if (foldersChanged) {
                // ソースフォルダを足した・外しただけ（並びは同じ。CacheFormat#headerReusable）。そのフォルダの
                // ファイルは、足した・消したファイルとして扱う（docs/cache-unification-qa.md の Q67）
                Log.info(Messages.get("analysis.cache.foldersChanged"));
            }
            oldFolders = CacheFormat.foldersOf(in.header());
            if (!head.intact()) {
                // L 行・T 行が書き換えられた・化けた。L 行を信用できなければ、どの jar が変わったかを言えない
                Log.info(Messages.get("analysis.cache.headDamaged"));
                return null;
            }
            return head.libraries();
        } catch (IOException | RuntimeException e) {
            // 読めない・文字が壊れているキャッシュ。全件解析し直せば済むので、解析ごと失敗させない
            Log.warn(Messages.format("analysis.cache.unreadable", e));
            return null;
        }
    }

    // ------------------------------------------------------------
    // 中断した前回の実行からの引き継ぎ
    // ------------------------------------------------------------

    /**
     * 前回の実行が途中で終わったときに残る一時ファイルを、引き継ぎ用に退避する。
     *
     * キャッシュは一時ファイルへ書いてから本物に差し替えるので、フェーズ1の途中で実行が終わると
     * 一時ファイルだけが残る。これを退避しておき、これから解析するファイルのぶんは
     * パースし直さずに書き写す（{@link #salvageFrom}）。
     * 退避しておくのは、これから書く一時ファイルと名前がぶつかるため。
     * ついでに、残りっぱなしになっていた一時ファイルの掃除にもなる。
     *
     * @return 引き継ぎに使うファイル。残っていない・引き継がない設定なら null
     */
    private Path takeOverPartial(Path cacheFile, Path tmpCache) {
        Path partial = cacheFile.resolveSibling(cacheFile.getFileName() + ".partial");
        try {
            // cache.enabled=false は「前の結果を使わない」指定なので、引き継ぎもしない
            if (!config.cacheEnabled) {
                Files.deleteIfExists(partial);
                return null;
            }
            if (Files.isRegularFile(tmpCache)) {
                Files.move(tmpCache, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            // 一時ファイルが無くても、前回が退避した直後に落ちていれば退避先が残っている
            return Files.isRegularFile(partial) ? partial : null;
        } catch (IOException e) {
            Log.warn(Messages.format("analysis.resume.cannotStash", e));
            return null;
        }
    }

    /**
     * 中断した前回の実行から、これから解析するファイルのブロックを書き写す。
     *
     * <p>退避した一時ファイルは<b>正しいキャッシュではない</b>（再利用ぶんのブロックが入っておらず、
     * 依存の判定も通っていない）。そのため「どのブロックが有効か」の判断には使わず、
     * <b>パースの使い回し</b>にだけ使う。引き継いだファイルは、解析したファイルとまったく同じ扱いで
     * 「変わったファイル」のまま（宣言する型は「変わった型」に入り、依存の判定も連鎖も回る）。
     * 変わるのは「JDT でパースするか、書き写すか」だけなので、差分更新の正しさの理屈には触れない。
     *
     * <p>引き継ぐ条件（{@link #isPartialUsable}）:
     * <ul>
     *   <li>ヘッダ（形式・ソースレベル・文字コード・JDK・JDT）が今回と一致する</li>
     *   <li>ソース一覧（T行。パス・サイズ・内容ハッシュ）が当時と丸ごと同じ。
     *       ブロックは他のファイルの内容にも依存するため</li>
     *   <li>依存 jar が当時から変わっていない。ブロックは当時のクラスパスでのバインディング解決の
     *       結果なので、jar が変われば同じソースでも呼び出し先や親型が変わりうる</li>
     *   <li>ファイルのサイズと内容ハッシュが一致する（{@link #isValidBlock}）</li>
     *   <li>ブロックの検査値が合う（旧キャッシュのパス1と同じ）</li>
     *   <li>ブロックが最後まで書けている。次の F 行か、最後まで書き終えた印（Z 行）に
     *       出会ったブロックだけを使う。最後の F 行から始まるブロックは、書き込みバッファの
     *       途中で切れている可能性があるので使わない</li>
     * </ul>
     *
     * @param sources   今回のソース一覧の指紋（T 行）
     * @param libraries 今回の依存 jar（L 行。{@link LibraryDiff#current}）
     * @param cacheOut  書き写す先。解析するファイルが無いとき（{@code toAnalyze} が空）は書かないので null でもよい
     * @return まだ解析が必要なファイル（引き継げたぶんを除いたもの）
     */
    private List<SourceFile> salvageFrom(Path partial, List<SourceFile> toAnalyze,
                                         Map<String, SourceFile> live, String sources,
                                         List<LibraryFact> libraries, BufferedWriter cacheOut,
                                         StaleTypes stale, CachePhaseResult result) throws IOException {
        Set<String> taken = new HashSet<>();
        if (isPartialUsable(partial, sources, libraries)) {
            Set<String> wanted = pathsOf(toAnalyze);
            if (!wanted.isEmpty()) {
                copySalvageable(partial, wanted, live, cacheOut, stale, result, taken);
            }
        }
        if (taken.isEmpty()) {
            return toAnalyze;
        }
        Log.info(Messages.format("analysis.resume.taken", taken.size()));
        List<SourceFile> rest = new ArrayList<>();
        for (SourceFile f : toAnalyze) {
            if (!taken.contains(f.relativePath())) {
                rest.add(f);
            }
        }
        return rest;
    }

    private static Set<String> pathsOf(List<SourceFile> files) {
        Set<String> paths = new HashSet<>(files.size() * 2);
        for (SourceFile f : files) {
            paths.add(f.relativePath());
        }
        return paths;
    }

    /** 引き継ぎに使った（あるいは使えなかった）退避ファイルを消す */
    private static void deletePartial(Path partial) {
        if (partial == null) {
            return;
        }
        try {
            Files.deleteIfExists(partial);
        } catch (IOException e) {
            Log.warn(Messages.format("analysis.resume.cannotDelete", e));
        }
    }

    /**
     * 退避した一時ファイルを引き継いでよいか。
     *
     * ヘッダ（形式・ソースレベル・文字コード・JDK・JDT・ソースフォルダの並び）・先頭の行の検査値・依存 jar・
     * <b>ソース一覧</b>がどれも当時と同じ（検査値は合う）ときだけ引き継ぐ。
     *
     * <p>ソース一覧まで見るのは、引き継ぐブロックが「そのファイルの内容」だけでなく
     * 「他のファイルの内容」にも依存するため。呼び出し先・親型・コンパイル時定数の値は
     * バインディング解決の結果なので、<b>別のファイルが変わっていれば、そのファイル自身が
     * 変わっていなくてもブロックは古い</b>。差分更新はこれを I 行の依存で見分けるが、
     * 引き継ぎのブロックは「今このファイルを解析した結果」として書き込むので依存の判定を通らない。
     * 判定を通す作りにもできるが（退避した一時ファイルを既存キャッシュと同じ扱いで読む）、
     * 引き継ぎが効いてほしい場面は「中断してすぐ同じソースで実行し直す」なので、
     * 一覧が丸ごと同じときだけに絞る簡単な形にした。違えば引き継がないだけで、
     * 差分更新はこれまでどおり動く。
     *
     * <p>依存 jar は、今回の L 行（パス0 で旧キャッシュと突き合わせた {@link LibraryDiff#current}）と比べる。
     * クラスパスを走査し直さない（クラスフォルダなら {@code .class} をすべて読み直すことになる）。
     * 比べ方は {@link LibraryDiff#unchangedSince} を参照（走査し直して突き合わせたときと同じ答えになる）。
     */
    private boolean isPartialUsable(Path partial, String sources, List<LibraryFact> libraries) {
        CacheHead head;
        try (CacheReader in = CacheReader.open(partial)) {
            head = headOf(in, false);
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.resume.readFailed", e));
            return false;
        }
        if (head == null) {
            Log.info(Messages.get("analysis.resume.incompatible"));
            return false;
        }
        if (!head.intact()) {
            Log.info(Messages.get("analysis.resume.headDamaged"));
            return false;
        }
        if (!head.sources().equals(sources)) {
            Log.info(Messages.get("analysis.resume.sourcesChanged"));
            return false;
        }
        LibraryDiff diff = LibraryDiff.unchangedSince(head.libraries(), libraries);
        if (diff.any()) {
            Log.info(Messages.format("analysis.resume.librariesChanged", diff));
            return false;
        }
        return true;
    }

    /**
     * 引き継げるブロックを新キャッシュへ書き写す。書き写せたファイルを taken に積む。
     * 検査値の合わないブロックは書き写さない（そのファイルは解析し直す）
     */
    private void copySalvageable(Path partial, Set<String> wanted, Map<String, SourceFile> live,
                                 BufferedWriter cacheOut, StaleTypes stale, CachePhaseResult result,
                                 Set<String> taken) throws IOException {
        try (CacheReader in = CacheReader.open(partial)) {
            List<String> block = new ArrayList<>();   // 直前の F 行から始まる、判定待ちのブロック
            BlockChecksum checksum = new BlockChecksum();
            String[] fileRow = null;                  // 判定待ちのブロックの F 行（引き継がないなら null）
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType != CacheFormat.ROW_FILE && rowType != CacheFormat.ROW_END) {
                    if (fileRow != null) {
                        block.add(in.line());
                        in.addTo(checksum);
                    }
                    continue;
                }
                // 次のブロックが始まった、または最後まで書き終えた印に出会った。
                // どちらでも、ここまでのブロックは書き終えている
                if (fileRow != null) {
                    if (checksum.hex().equals(CacheFormat.crcOf(fileRow))) {
                        flushSalvaged(fileRow, block, cacheOut, stale, result, taken);
                    } else {
                        damagedBlocks++;
                    }
                }
                fileRow = null;
                block.clear();
                checksum.reset();
                if (rowType == CacheFormat.ROW_END) {
                    continue;
                }
                String[] f = in.columns();
                // 同じクラスが 2 つのソースフォルダにあるファイルは引き継がない（組のもう片方と一緒に解析し直す。
                // 片方だけ引き継ぐと、組が別々のバッチで解析されたことになる。SameUnitFiles）
                if (f.length >= 2 && wanted.contains(f[1]) && !taken.contains(f[1]) && !units.contains(f[1])
                        && isValidBlock(f, live)) {
                    fileRow = f;
                    block.add(in.line());   // F 行の中身（パス・サイズ・ハッシュ）は今と一致している
                    in.addWithoutLastColumnTo(checksum);
                }
            }
            // 最後の F 行から始まるブロックは、途中で切れている可能性があるので使わない
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.resume.readFailedPartial", e));
        }
    }

    /**
     * 引き継ぐブロック1件を書き写し、宣言する型と未解決の件数を数える。
     * 数え方は解析したときと同じにする（{@link Cascade#ALWAYS} 相当）。
     * 未解決の件数は F 行の列から取る（U 行は読まない）
     *
     * @param fileRow ブロックの F 行の列
     * @param block   F 行を含むブロックの行（ファイルに書かれたまま）
     */
    private static void flushSalvaged(String[] fileRow, List<String> block, BufferedWriter cacheOut,
                                      StaleTypes stale, CachePhaseResult result, Set<String> taken)
            throws IOException {
        String rel = fileRow[1];
        // 引き継いだファイルも、解析したファイルと同じくエラーを数える（数えないと警告が消える）
        result.countErrors(rel, CacheFormat.errorsOf(fileRow), CacheFormat.syntaxErrorsOf(fileRow));
        result.unresolved += CacheFormat.unresolvedOf(fileRow);
        for (String line : block) {
            writeLine(cacheOut, line);
            if (CacheFormat.rowTypeOf(line) == CacheFormat.ROW_TYPE) {
                TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                if (t != null) {
                    stale.register(t);
                    stale.packageNow(t.pkg());
                    stale.addDeclared(t.typeFqn(), t.pkg());
                }
            }
        }
        result.salvaged++;
        taken.add(rel);
    }

    /**
     * キャッシュのヘッダの直後にある情報。
     *
     * @param libraries  解析時の依存 jar（L行）
     * @param sources    解析開始時のソース一覧の指紋（T行）。無ければ空文字
     * @param intact     先頭の行の検査値（T 行の最後の列）が合うか。合わなければ L 行・T 行を信用しない
     */
    private record CacheHead(List<LibraryFact> libraries, String sources, boolean intact) {
    }

    /**
     * ヘッダが今回と一致すれば、続く T 行・L 行を返す。一致しなければ null。
     * 既存キャッシュ（パス0）と、中断した前回の実行の一時ファイル（引き継ぎ）で共通。
     * 先頭の行の検査値（T 行の最後の列。{@link #headLinesOf}）も確かめる。T 行が無い・2 つある・L 行が T 行より
     * 後ろにある・検査値が合わないなら {@link CacheHead#intact} が false
     *
     * @param foldersMayChange ソースフォルダを足した・外しただけのヘッダも一致とみなすか（既存キャッシュは true。
     *                         {@link CacheReader#headerReusable}）。引き継ぎはソースの一覧が丸ごと同じときだけなので false
     */
    private CacheHead headOf(CacheReader in, boolean foldersMayChange) throws IOException {
        String expected = expectedHeader();
        if (!(foldersMayChange ? in.headerReusable(expected) : in.headerMatches(expected))) {
            return null;
        }
        BlockChecksum checksum = new BlockChecksum();
        checksum.add(in.header());
        List<LibraryFact> libraries = new ArrayList<>();
        String sources = "";
        String expectedCrc = null;
        boolean wellFormed = true;
        while (in.next()) {
            if (in.is(CacheFormat.ROW_LIBRARY)) {
                wellFormed &= (expectedCrc == null);   // L 行は T 行より前
                in.addTo(checksum);
                LibraryFact l = LibraryFact.fromRow(in.columns());
                if (l != null) {
                    libraries.add(l);
                }
            } else if (in.is(CacheFormat.ROW_SOURCES)) {
                wellFormed &= (expectedCrc == null);   // T 行は 1 つだけ
                in.addWithoutLastColumnTo(checksum);
                sources = in.column(1);
                expectedCrc = CacheFormat.headCrcOf(in.columns());
            } else {
                break;   // ブロックが始まった
            }
        }
        return new CacheHead(libraries, sources,
                wellFormed && expectedCrc != null && expectedCrc.equals(checksum.hex()));
    }

    /**
     * 解析対象のソース一覧の指紋（相対パス・サイズ・内容ハッシュ）。
     *
     * 引き継ぎの判定に使う。1ファイルぶんの同一性が一致していても、他のファイルが
     * 変わっていればそのブロックの解決結果は古いので、一覧が丸ごと同じときだけ引き継ぐ。
     *
     * <p>中身は差分更新の同一性（{@link #isValidBlock}）と同じ3つ立て。更新時刻は入れない
     * （クラスの説明「同一性」、docs/cache-identity-qa.md）。
     *
     * <p>全ファイルのハッシュが要るが、読むのは 1 ファイル 1 回だけ（{@link #hashOf}）。
     * 差分更新の判定（パス1 の {@link #isValidBlock}）と、これから解析するファイルの F 行と、
     * この指紋とで同じハッシュを使い回す。
     */
    private String fingerprintOf(Map<String, SourceFile> live) {
        List<String> lines = new ArrayList<>(live.size());
        for (SourceFile f : live.values()) {
            lines.add(f.relativePath() + "\t" + f.size() + "\t" + hashOf(f));
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return FileHash.ofText(sb.toString());
    }

    /**
     * パス1 で読んでいる途中の旧キャッシュのブロック 1 つ。ブロックの終わり（次の F 行・Z 行・
     * ファイルの終わり）で {@link #finishOldBlock} が判定する。検査値はブロックを読み終えるまで
     * 分からないので、判定に要るものをここに溜めておく（1 ブロック分だけ）
     */
    private static final class OldBlock {
        /** F 行の相対パス。F 行が壊れていれば null */
        final String rel;
        /** そのファイルが今のソースにあるか（無ければ、壊れていても解析し直さないので数えない） */
        final boolean inSources;
        /** F 行のサイズと内容ハッシュが今のソースと一致するか */
        final boolean identical;
        /** F 行のエラー数・構文エラー数・未解決数 */
        final int errors;
        final int syntaxErrors;
        final int unresolved;
        final String expectedCrc;
        /** F 行の先頭のファイル上の位置（バイト） */
        final long start;
        /** F 行を読んだ時点の {@link CacheReader#irregularities}（ブロックが書き手の書くとおりの形かを見る） */
        final long irregularAtStart;
        final BlockChecksum checksum = new BlockChecksum();
        /** H 行（ファイルに書かれたまま）。部分型の索引に足し、無効なブロックなら「変わった型」に加える */
        final List<String> typeRows = new ArrayList<>();
        /** 理由が BINDING_FAILED の U 行があったか（jar の追加・変更と、新しい型で解析し直すかを見る） */
        boolean bindingFailed;
        /** F 行の直後の行をまだ読んでいないか */
        boolean firstRow = true;
        /** I 行（F 行の直後）の依存。I 行が無ければ空 */
        String deps = "";
        /** I 行の解決できなかった名前（新しい型で解析し直すか）。I 行が無ければ空（何にでも当たる） */
        String names = "";
        /** I 行の自分の宣言の指紋（宣言の連鎖。{@link #oldDeclarations}）。I 行が無ければ空 */
        String declarations = "";

        OldBlock(String[] f, boolean inSources, boolean identical, long start, long irregularAtStart) {
            this.rel = (f.length >= 2) ? f[1] : null;
            this.inSources = inSources;
            this.identical = identical;
            this.errors = CacheFormat.errorsOf(f);
            this.syntaxErrors = CacheFormat.syntaxErrorsOf(f);
            this.unresolved = CacheFormat.unresolvedOf(f);
            this.expectedCrc = CacheFormat.crcOf(f);
            this.start = start;
            this.irregularAtStart = irregularAtStart;
        }
    }

    /**
     * パス1 で読んだ旧キャッシュのうち、パス5 で書き写す候補（有効だったブロック）と、ファイル全体の形。
     *
     * <p>ブロックごとに持つのは、パス（今のソース一覧 {@code live} の文字列そのもの）・ファイル上の範囲
     * （F 行の先頭から、次の F 行・Z 行の手前まで）・F 行の件数（エラー数・構文エラー数・未解決数）だけで、
     * 有効なブロックの数に比例する小さな配列に持つ。パス5 はこの範囲をバイトのまま書き写し、件数もここから数える
     * （旧キャッシュを行として読み直さない）。
     */
    private static final class OldCache {
        String[] paths = new String[64];
        long[] starts = new long[64];
        long[] ends = new long[64];
        int[] errors = new int[64];
        int[] syntaxErrors = new int[64];
        int[] unresolved = new int[64];
        /** 宣言する型のパッケージ（最初の H 行。無ければ null）。同じ中身の文字列は 1 つを共有する */
        String[] packages = new String[64];
        /** 書き手の書くとおりの形でないブロック（空行・CRLF を含む）。書き写すときは行に戻して書き直す */
        final BitSet irregular = new BitSet();
        /**
         * 型解決に失敗していたブロック（F 行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）。
         * 新しい型があれば解析し直す（クラスの説明「新しい型」）
         */
        final BitSet unresolvedTypes = new BitSet();
        /**
         * 型解決に失敗していたブロックの、解決できなかった名前（I 行。カンマ区切り）。
         * 失敗していないブロックは null。変わった型に当たるものだけを解析し直す（{@link StaleTypes#matchesChangedType}）
         */
        String[] unresolvedNames = new String[64];
        /** {@link #packages} の文字列を共有するための表 */
        private final Map<String, String> packageNames = new HashMap<>();
        int size;

        /** F 行の数 */
        long blocks;
        /** 今のソースに無いファイル（消した・外したフォルダの）のブロックのパス（{@link SameUnitFiles#pairedWithDeleted}） */
        final List<String> deleted = new ArrayList<>();
        /** どのブロックも有効だったか（書き写す候補になったか） */
        boolean allKept = true;
        /** 最初の F 行（ブロックが無ければ Z 行）の位置。ここより前はヘッダ・L 行・T 行 */
        long blocksStart = -1;
        /** ファイル全体が書き手の書くとおりの形か（空行・CRLF・改行で終わらない行が無く、Z 行が 1 つだけ） */
        boolean writtenAsIs;

        void add(String path, long start, long end, int errorCount, int syntaxErrorCount, int unresolvedCount,
                 boolean irregularBlock, boolean failedTypes, String names, String pkg) {
            if (size == paths.length) {
                int grown = size + (size >> 1) + 16;
                paths = Arrays.copyOf(paths, grown);
                packages = Arrays.copyOf(packages, grown);
                unresolvedNames = Arrays.copyOf(unresolvedNames, grown);
                starts = Arrays.copyOf(starts, grown);
                ends = Arrays.copyOf(ends, grown);
                errors = Arrays.copyOf(errors, grown);
                syntaxErrors = Arrays.copyOf(syntaxErrors, grown);
                unresolved = Arrays.copyOf(unresolved, grown);
            }
            paths[size] = path;
            starts[size] = start;
            ends[size] = end;
            errors[size] = errorCount;
            syntaxErrors[size] = syntaxErrorCount;
            unresolved[size] = unresolvedCount;
            if (irregularBlock) {
                irregular.set(size);
            }
            if (failedTypes) {
                unresolvedTypes.set(size);
                unresolvedNames[size] = names;
            }
            packages[size] = (pkg == null) ? null : packageNames.computeIfAbsent(pkg, k -> k);
            size++;
        }
    }

    /**
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     *
     * <p>ブロックごとに検査値（F 行の crc）を計算し直して突き合わせる。合わないブロックは
     * 書き換えられた・化けたもので、中身を信用できないので有効にしない（そのファイルは
     * パス2 で解析し直す。ほかのブロックはそのまま再利用する）。
     *
     * <p>最後まで書き終えたキャッシュか（最終行の印。{@link CacheFormat#trailerFor}）もここで見る。
     * 途中で切れたキャッシュは、切れた場所より前のブロックが「サイズもハッシュも一致する」ように
     * 見えるため、そのまま再利用すると呼び出しが静かに欠ける。印が無い・ブロック数が合わなければ
     * null を返して丸ごと捨てさせる。読めない（文字が壊れている）ときも同じ。
     *
     * <p>宣言の連鎖のために、有効なブロックの自分の宣言の指紋（I 行の 3 列目）もここで覚える
     * （{@link #oldDeclarations}）。
     *
     * <p>旧キャッシュを行として読むのは実行ごとにこの 1 回だけにする。あとで要るものはここで取っておく。
     * <ul>
     *   <li>パス3 が見る依存（I 行）… 有効なブロックのぶんを一時ファイル（{@link DepsIndex}）に書く。
     *       ヒープには持たない（依存の型名はブロックの数に比例して多い）。書けなくても続ける
     *       （パス3 が旧キャッシュから読む）</li>
     *   <li>パス5 が書き写すブロックの範囲と F 行の件数 … {@link OldCache} に持つ</li>
     * </ul>
     *
     * @param librariesAddedOrChanged jar が追加・変更されたか。そのときは型解決に失敗していたブロック
     *                                （F行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）を
     *                                有効から外し、libraryAffected に積む
     * @param deps                    有効なブロックの依存を書く索引（依存の無いブロックは書かない）
     * @return 旧キャッシュをそのまま使ってよければ、書き写す候補。途中で切れている・読めないなら null
     */
    private OldCache scanOldCache(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                  boolean librariesAddedOrChanged, Set<String> libraryAffected, DepsIndex deps) {
        OldCache old = new OldCache();
        int damaged = 0;   // 丸ごと捨てるときは数えないので、読み終えるまで damagedBlocks に足さない
        int trailers = 0;
        String lastLine = "";
        // 索引への書き込みの失敗はここでは受けない（索引の側で受けて、パス3 を旧キャッシュから読む形に切り替える）。
        // 受けると「既存キャッシュを読めない」と取り違えて、読めているキャッシュを丸ごと捨ててしまう
        try (CacheReader in = CacheReader.open(oldChannel)) {   // ヘッダはパス0で検証済み
            checkOldCacheUnchanged();
            OldBlock block = null;
            while (in.next()) {
                char rowType = in.rowType();
                lastLine = in.line();
                if (rowType == CacheFormat.ROW_FILE || rowType == CacheFormat.ROW_END) {
                    if (old.blocksStart < 0) {
                        old.blocksStart = in.lineStart();
                    }
                    if (block != null) {
                        damaged += finishOldBlock(block, in.lineStart(), in.irregularities(), live, valid, stale,
                                librariesAddedOrChanged, libraryAffected, old, deps);
                        block = null;
                    }
                    if (rowType == CacheFormat.ROW_FILE) {
                        old.blocks++;
                        String[] f = in.columns();
                        boolean inSources = f.length >= 2 && live.containsKey(f[1]);
                        if (!inSources && f.length >= 2) {
                            old.deleted.add(f[1]);
                        }
                        block = new OldBlock(f, inSources, isValidBlock(f, live), in.lineStart(),
                                in.irregularities());
                        // F 行の件数（エラー数・未解決数など）も検査値で守る（crc 列だけを空にした形で足す）
                        in.addWithoutLastColumnTo(block.checksum);
                    } else {
                        trailers++;
                    }
                    continue;
                }
                if (block == null) {
                    continue;   // 最初のブロックより前（L 行・T 行）
                }
                if (block.firstRow) {
                    // F 行の直後。I 行なら依存（パス3 が見る）。無ければ依存なしとみなす
                    block.firstRow = false;
                    if (rowType == CacheFormat.ROW_DEPENDENCIES) {
                        String[] cols = in.columns();
                        block.deps = CacheFormat.columnAt(cols, 1);
                        block.names = CacheFormat.columnAt(cols, 2);
                        block.declarations = CacheFormat.columnAt(cols, 3);
                    }
                }
                in.addTo(block.checksum);
                if (rowType == CacheFormat.ROW_TYPE) {
                    block.typeRows.add(in.line());
                } else if (!block.bindingFailed && rowType == CacheFormat.ROW_UNRESOLVED
                        && UnresolvedCallFact.BINDING_FAILED.equals(
                                UnresolvedCallFact.reasonColumn(in.columns()))) {
                    // エラーとしては報告されなかったが呼び出し先が解決できなかった。jar の追加や新しい型で変わりうる
                    block.bindingFailed = true;
                }
            }
            if (block != null) {
                damaged += finishOldBlock(block, in.nextLineStart(), in.irregularities(), live, valid, stale,
                        librariesAddedOrChanged, libraryAffected, old, deps);
            }
            old.writtenAsIs = (in.irregularities() == 0 && trailers == 1);
            // 読んでいるあいだに書き換えられていれば、読んだものを信用しない（丸ごと捨てて全件解析し直す）
            checkOldCacheUnchanged();
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.cache.unreadable", e));
            return null;
        }
        if (!lastLine.equals(CacheFormat.trailerFor(old.blocks))) {
            Log.info(Messages.format("analysis.cache.truncated", old.blocks));
            return null;
        }
        damagedBlocks += damaged;
        deps.finishWriting();
        return old;
    }

    /**
     * パス1 で 1 ブロックを読み終えたときの判定。
     *
     * <ul>
     *   <li>検査値が合い、サイズと内容ハッシュも一致する … 有効（jar が追加・変更されていて、
     *       型解決に失敗していたブロックなら libraryAffected）。有効なら書き写す候補（{@code old}）に積み、
     *       依存（I 行）を索引（{@code deps}）に書く</li>
     *   <li>それ以外 … 無効。宣言していた型（H 行）を「変わった型」に加える
     *       （改名・削除された型を参照していたファイルを解析し直すため）</li>
     * </ul>
     *
     * @param end              ブロックの終わり（次の F 行・Z 行の先頭）のファイル上の位置
     * @param irregularAtEnd   そのときの {@link CacheReader#irregularities}
     * @return 検査値が合わず、そのファイルを解析し直すブロックなら 1（ログの件数に数える）。それ以外は 0
     */
    private int finishOldBlock(OldBlock block, long end, long irregularAtEnd, Map<String, SourceFile> live,
                               Set<String> valid, StaleTypes stale, boolean librariesAddedOrChanged,
                               Set<String> libraryAffected, OldCache old, DepsIndex deps) {
        boolean intact = block.checksum.hex().equals(block.expectedCrc);
        // 置き場所のフォルダのパッケージも前回のパッケージに数える（JDT はパッケージがあるかをフォルダで決める。
        // 型を宣言しないファイル・解析に失敗したファイルだけのパッケージも、できた・無くなったと分かる）。
        // 今回のぶんは run の最初に今のソースの一覧から数える
        String unitPackage = (block.rel == null) ? null
                : StaleTypes.packageOfUnit(SameUnitFiles.unitNameOf(block.rel, oldFolders));
        stale.packageBefore(unitPackage);
        // 親型の関係はどのブロックのものも部分型の索引に足す（有効なブロックの型が、無効になったブロックや
        // 今回解析するファイルの型の部分型かもしれない。壊れたブロックの関係を足しても、解析し直すファイルが増えるだけ）
        List<TypeFact> declared = new ArrayList<>(block.typeRows.size());
        for (String row : block.typeRows) {
            TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(row));
            if (t != null) {
                stale.register(t);
                stale.packageBefore(t.pkg());
                declared.add(t);
            }
        }
        if (intact && block.identical) {
            // 有効なブロックは今のソースにある（isValidBlock）。パスは今のソース一覧の文字列を使い回す
            String rel = live.get(block.rel).relativePath();
            if (librariesAddedOrChanged && (block.errors > 0 || block.bindingFailed)) {
                libraryAffected.add(rel);   // 宣言する型はパス2の解析時に「変わった型」へ入る
                old.allKept = false;
            } else {
                valid.add(rel);
                if (!block.declarations.isEmpty()) {
                    oldDeclarations.put(rel, block.declarations);
                }
                for (TypeFact t : declared) {
                    stale.packageNow(t.pkg());
                }
                old.add(rel, block.start, end, block.errors, block.syntaxErrors, block.unresolved,
                        irregularAtEnd != block.irregularAtStart, block.errors > 0 || block.bindingFailed,
                        block.names, declared.isEmpty() ? null : declared.get(0).pkg());
                if (!block.deps.isEmpty()) {
                    deps.add(old.size - 1, block.deps);
                }
            }
            return 0;
        }
        old.allKept = false;
        for (TypeFact t : declared) {
            stale.add(t.typeFqn(), t.pkg());
        }
        if (declared.isEmpty() && unitPackage != null && !declaresNoType(block.rel)) {
            // 型を 1 つも宣言していなかったブロック（解析に失敗したファイルの印のブロック。BlockWriter#failed。
            // エラーで JDT が型を落としたファイル・壊れて H 行を読めないブロックも）。前回そのファイルが宣言していた型が
            // 分からないので、置き場所のパッケージを中身の分からないパッケージにする（変わった jar のパッケージと同じ扱い）
            stale.addOpaque(unitPackage);
        }
        // 今のソースに無いファイルのブロックは、壊れていても解析し直さないので数えない
        return (!intact && block.inSources) ? 1 : 0;
    }

    /** 型を宣言しないコンパイル単位（{@code package-info.java}・{@code module-info.java}）か。相対パスで見る */
    private static boolean declaresNoType(String relativePath) {
        return relativePath.endsWith("package-info.java") || relativePath.endsWith("module-info.java");
    }

    /**
     * パス1 が書き、パス3 が読む依存の索引（一時ファイル。{@link TempFiles#DEPS}）。1 行に 1 ブロック、
     * {@code 番号 依存}（番号は {@link OldCache} の添字。{@link CacheFormat#joinRow} で符号化）を
     * 旧キャッシュのブロックの順に書く。
     * 最初の行を書くときにファイルを作る（書くものが無ければ作らない）。
     *
     * <p>名前は実行ごとに違い（{@link TempFiles#create}）、書くのも読むのも作ったときに開いた 1 つのチャネルで行う
     * （パス3 の周回ごとに先頭へ戻す。名前で開き直さない）。同じキャッシュのフォルダを使う別の実行が
     * 残り物として消しても読めるし、前の実行の残り物を読むこともない。書いた行数を数えておき、読んだ行数が
     * 違えば例外にする（解析は失敗として終わる。依存を読み落として再解析を静かに飛ばすよりよい）。
     *
     * <p>書けなかった（ディスクの空きが無い・権限が無い）ときは、索引を捨てて {@link #unwritable} を立てる。
     * 旧キャッシュは読めているので、キャッシュを捨てたりせず、パス3 は旧キャッシュから I 行を読む
     * （{@link CacheUpdater#selectDependentsFromCache}）。利用者が対処することではないので {@code Log.info} で知らせる。
     * {@link #close} で閉じて消す
     */
    private static final class DepsIndex implements Closeable {
        private final Path cacheFile;
        private Path file;
        private FileChannel channel;
        private BufferedWriter out;
        /** 書いた行の数 */
        private long lines;
        /** 書けなかった。パス3 は旧キャッシュから読む */
        private boolean unwritable;

        DepsIndex(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        /** 1 ブロックの依存を書く（{@code index} は {@link OldCache} の添字）。書けなければ索引を捨てる（例外は投げない） */
        void add(int index, String deps) {
            if (unwritable) {
                return;
            }
            try {
                if (out == null) {
                    file = TempFiles.create(cacheFile, TempFiles.DEPS);
                    channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    // 閉じない（閉じるとチャネルも閉じる）。書き終えたら flush だけする
                    out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
                            StandardCharsets.UTF_8.newEncoder()));
                }
                writeLine(out, CacheFormat.joinRow(String.valueOf(index), deps));
                lines++;
            } catch (IOException | RuntimeException e) {
                giveUp(e);
            }
        }

        /** パス1 を読み終えた。溜めた分をファイルへ出す（チャネルは開いたまま。パス3 が読む） */
        void finishWriting() {
            if (out == null || unwritable) {
                return;
            }
            try {
                out.flush();
            } catch (IOException | RuntimeException e) {
                giveUp(e);
            }
        }

        /** 書けなかった。索引を捨て、以後は書かない（パス3 は旧キャッシュから読む） */
        private void giveUp(Exception e) {
            Log.info(Messages.format("analysis.cache.depsIndexUnwritable",
                    file != null ? file : cacheFile.toAbsolutePath().getParent(), e));
            unwritable = true;
            out = null;
            close();
        }

        /** 書けたか（false なら、パス3 は旧キャッシュから読む） */
        boolean usable() {
            return !unwritable;
        }

        /**
         * 書いた行を先頭から順に渡す（パス3 の 1 周ぶん）。1 行も書いていなければ何も渡さない。
         *
         * @throws IOException 読めない、または読んだ行数が書いた行数と違う
         */
        void forEach(DepsConsumer each) throws IOException {
            if (channel == null) {
                if (lines != 0) {
                    throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, 0));
                }
                return;
            }
            channel.position(0);
            // 閉じない（閉じるとチャネルも閉じる。次の周回でまた読む）
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8.newDecoder()));
            long read = 0;
            String line;
            while ((line = in.readLine()) != null) {
                read++;
                String[] cols = CacheFormat.columnsOf(line);
                each.accept(Integer.parseInt(cols[0]), CacheFormat.columnAt(cols, 1));
            }
            if (read != lines) {
                throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, read));
            }
        }

        /** 閉じて消す */
        @Override
        public void close() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                Log.info(Messages.format("cache.tempNotDeleted", file, e));
            }
            channel = null;
            TempFiles.delete(file);
        }
    }

    /** 依存の索引の 1 行（{@link OldCache} の添字と依存）を受け取る */
    @FunctionalInterface
    private interface DepsConsumer {
        void accept(int index, String deps);
    }

    /**
     * パス3・パス4。「変わった型」に触れる有効ブロックを再解析に回し、解析し直した結果として
     * 「変わった型」が増えていたら（宣言の連鎖。{@link CacheUpdater} のクラスコメント参照）もう一周する。
     *
     * ふつうは1周で止まる。周回が続くのは、解析し直したファイルの宣言か定数の値が変わったとき（数珠つなぎに
     * なっているとき）と、jar の変化で解析し直したファイルがあったときだけ。1周ごとに valid は減るだけで増えないので、必ず止まる。
     *
     * <p>パス3 は、パス1 が書いた依存の索引を先頭から読み、まだ有効なブロックのうち「変わった型」または
     * 「変わった jar のパッケージ」に触れるものを valid から外し、再解析の一覧に積む（旧キャッシュのブロックの順）。
     * 索引に無いブロックは依存が無い（どの型にも触れない）。旧キャッシュは読み直さない（索引を書けなかったときだけ
     * 読む。{@link #selectDependentsFromCache}）。ここでは何も書き出さない。書き写しは、連鎖が止まってから
     * {@link #copyValidBlocks} で行う。
     *
     * @param deps 依存の索引
     * @param old  パス1 で覚えたブロックの位置（索引を書けなかったときに、旧キャッシュから I 行を読むのに使う）
     */
    private void reanalyzeDependents(CallEdgeExtractor extractor, BlockWriter writer,
                                     Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                     DepsIndex deps, OldCache old)
            throws IOException {
        // 今のソースのパッケージはパス2 でそろった。できた・無くなったパッケージを決める
        stale.endOfSources();
        if (stale.isEmpty()) {
            return;   // 触れる先が無いので、読み直すだけ無駄
        }
        int mark;
        boolean first = true;
        do {
            mark = stale.mark();
            List<String> dependents = new ArrayList<>();
            List<String> libraryDependents = new ArrayList<>();
            if (first) {
                // 型とパッケージの衝突（JLS 7.1）。自分のパッケージの下にパッケージができた・無くなった・変わった jar の
                // パッケージがあるブロック。I 行に載らない（自分の型の名前が衝突する）ので依存では見つけられない。
                // パッケージは周回で変わらないので、最初の周回だけ見る（StaleTypes#collidesWithChangedPackage）
                for (int i = 0; i < old.size; i++) {
                    Reason collides = stale.collidesWithChangedPackage(old.packages[i]);
                    if (collides != Reason.UNTOUCHED && valid.remove(old.paths[i])) {
                        (collides == Reason.BY_SOURCE ? dependents : libraryDependents).add(old.paths[i]);
                    }
                }
                first = false;
            }
            // 型解決に失敗していたブロックのうち、解決できなかった名前が変わった型（新しい型を含む）に当たるもの。
            // 無い型・見えない型の名前は依存（I 行の型）に残らないので、依存では見つけられない（クラスの説明
            // 「新しい型」、docs/cache-unification-qa.md の Q53）。連鎖で変わった型が増えるので、周回ごとに見直す。
            // 中身の分からないパッケージ（解析に失敗したファイルの）の型になりうる名前も当たる（StaleTypes#namesUnderOpaque）
            for (int i = old.unresolvedTypes.nextSetBit(0); i >= 0; i = old.unresolvedTypes.nextSetBit(i + 1)) {
                String names = old.unresolvedNames[i];
                boolean opaque = stale.namesUnderOpaque(names, old.packages[i]);
                if (opaque || stale.namesUnderLibrary(names)) {
                    // 連鎖させるかは、どの理由で選ばれたか（型とパッケージの衝突で先に選ばれた場合も）に依らない（Q89）
                    writer.jarDriven.add(old.paths[i]);
                }
                if (valid.contains(old.paths[i]) && (opaque || stale.matchesChangedType(names))) {
                    valid.remove(old.paths[i]);
                    dependents.add(old.paths[i]);
                }
            }
            DepsConsumer select = (index, depsCsv) -> {
                String rel = old.paths[index];
                // jar（か中身の分からないパッケージ）に触れていたかは、ほかの理由で選ばれた（名前が当たった・ソースの変化にも
                // 触れた・同じ名前のファイルの組として引き込まれた）ファイルでも見る。連鎖は選ばれた理由ではなくこれで決める（Q89）
                if (stale.touchesLibrary(depsCsv, old.packages[index])
                        || stale.touchesOpaque(depsCsv, old.packages[index])) {
                    writer.jarDriven.add(rel);
                }
                if (!valid.contains(rel)) {
                    return;   // すでに再解析に回した
                }
                Reason touched = stale.touches(depsCsv, old.packages[index]);
                if (touched == Reason.BY_SOURCE) {
                    valid.remove(rel);
                    dependents.add(rel);
                } else if (touched == Reason.BY_LIBRARY) {
                    valid.remove(rel);
                    libraryDependents.add(rel);
                }
            };
            if (deps.usable()) {
                deps.forEach(select);
            } else {
                selectDependentsFromCache(old, select);
            }
            // 同じクラスが 2 つのソースフォルダにあるとき、その組は必ず一緒に解析する（SameUnitFiles）
            units.pullIntoPaths(dependents, libraryDependents, valid);
            writer.countAs = Reason.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
        } while (stale.mark() != mark && !valid.isEmpty());
    }

    /**
     * パス3 で依存の索引が使えない（書けなかった）ときの代わり。旧キャッシュを読み、パス1 で書き写す候補にした
     * ブロック（{@code old} に覚えた F 行の位置）の I 行を、索引に書くはずだったのと同じ順に渡す
     * （索引を使う前の読み方。ブロックの位置で照らすので、同じパスのブロックが 2 つある壊れたキャッシュでも
     * 索引と同じものを読む）。
     *
     * @throws IOException 読めない、または覚えた位置に F 行が無い（実行の途中で旧キャッシュが書き換えられた）
     */
    private void selectDependentsFromCache(OldCache old, DepsConsumer select) throws IOException {
        if (old.size == 0) {
            return;
        }
        int k = 0;
        int pending = -1;   // F 行を読んだ、依存の判定待ちのブロック（OldCache の添字）
        checkOldCacheUnchanged();
        try (CacheReader in = CacheReader.openAt(oldChannel, old.starts[0])) {
            while (in.next()) {
                char rowType = in.rowType();
                boolean blockStart = rowType == CacheFormat.ROW_FILE || rowType == CacheFormat.ROW_END;
                if (pending >= 0) {
                    // F 行の直後。I 行なら依存、無ければ依存なし（パス1 と同じ見方）
                    select.accept(pending, (!blockStart && rowType == CacheFormat.ROW_DEPENDENCIES)
                            ? in.column(1) : "");
                    pending = -1;
                }
                if (k == old.size) {
                    break;
                }
                if (rowType == CacheFormat.ROW_FILE && in.lineStart() == old.starts[k]) {
                    pending = k++;
                }
            }
        }
        if (pending >= 0) {
            select.accept(pending, "");
        }
        if (k != old.size) {
            // 依存を読み落としたまま進めると、解析し直すべきファイルを静かに再利用してしまう
            throw new IOException(Messages.format("analysis.cache.changedWhileReading", config.cacheFile));
        }
    }

    /**
     * パス5。最後まで有効だったブロックを、旧キャッシュでの順のまま新キャッシュへ書き写す。
     *
     * F 行も含めてそのまま書き写す。F 行の中身（パス・サイズ・内容ハッシュ・検査値）は、有効だと
     * 判定した時点で今のファイルと一致しているので、書き直す必要がない。
     * L 行はブロックの外（先頭）にあり、ここでは書き写さない（新しいものを先頭に書いている）。
     *
     * <p>パス1 で覚えたブロックの範囲を、行に戻さずバイトのまま写す（{@link FileChannel#transferTo}。
     * 隣り合うブロックはまとめて 1 回で写す）。書き手の書くとおりの形でないブロック（空行・CRLF を含む。
     * 手で直した・改行を変換したキャッシュ）だけは行に戻し、{@code '\n'} で書き直す（行として読んで
     * 書き直していたときと同じバイト列にするため）。
     *
     * 書き写したブロックの数を {@code result.reused} に、そこに含まれる型解決できなかった
     * 呼び出しの件数（F 行の未解決数）を {@code result.unresolved} に足す。数は最終行
     * （{@link CacheFormat#trailerFor}）にも出すので、valid の件数ではなく実際に書いた数を数える。
     */
    private void copyValidBlocks(OldCache old, Set<String> valid, FileChannel out, BufferedWriter cacheOut,
                                 CachePhaseResult result) throws IOException {
        // パス1 で読んだのと同じチャネルから写す（名前で開き直すと、そのあいだに差し替えられたファイルの、
        // パス1 で覚えた位置とは関係の無いバイトを写しかねない）。大きさが変わっていれば止める
        checkOldCacheUnchanged();
        FileChannel in = oldChannel;
        long pendingStart = -1;   // まだ写していない、隣り合うブロックをまとめた範囲
        long pendingEnd = -1;
        for (int i = 0; i < old.size; i++) {
            String rel = old.paths[i];
            if (!valid.contains(rel)) {
                continue;   // パス3 で解析し直した
            }
            result.reused++;
            // 前の実行でエラーだったファイルは、書き写した今回もエラーのままである。
            // ここで数えないと、2回目以降の実行で警告が消えてしまう
            result.countErrors(rel, old.errors[i], old.syntaxErrors[i]);
            result.unresolved += old.unresolved[i];
            if (old.irregular.get(i)) {
                transfer(in, pendingStart, pendingEnd, out, cacheOut);
                pendingStart = -1;
                pendingEnd = -1;
                rewriteLines(old.starts[i], old.ends[i], cacheOut);
            } else if (pendingEnd == old.starts[i]) {
                pendingEnd = old.ends[i];
            } else {
                transfer(in, pendingStart, pendingEnd, out, cacheOut);
                pendingStart = old.starts[i];
                pendingEnd = old.ends[i];
            }
        }
        transfer(in, pendingStart, pendingEnd, out, cacheOut);
        checkOldCacheUnchanged();
    }

    /**
     * 旧キャッシュの {@code [start, end)} を新キャッシュの今の位置へバイトのまま写す（{@code start < 0} なら何もしない）。
     * それまでに {@code cacheOut} へ書いた行を先に吐き出してから写す（同じファイルの続きに並ぶように）
     */
    private void transfer(FileChannel in, long start, long end, FileChannel out, BufferedWriter cacheOut)
            throws IOException {
        if (start < 0 || end <= start) {
            return;
        }
        cacheOut.flush();
        long at = out.position();
        long done = 0;
        long count = end - start;
        while (done < count) {
            long n = in.transferTo(start + done, count - done, out);
            if (n <= 0) {
                // パス1 で読んだ範囲が読めない（実行の途中で旧キャッシュが書き換えられた）
                throw new EOFException(config.cacheFile + " @" + (start + done));
            }
            done += n;
            // 書いた先の位置を明示しておく（続きの行の書き出しと次の転送が、写した範囲の直後から始まるように）
            out.position(at + done);
        }
    }

    /** 旧キャッシュの {@code [start, end)} を行に戻し、空行を除いて {@code '\n'} で書き直す */
    private void rewriteLines(long start, long end, BufferedWriter cacheOut) throws IOException {
        try (CacheReader in = CacheReader.openAt(oldChannel, start)) {
            while (in.next() && in.lineStart() < end) {
                writeLine(cacheOut, in.line());
            }
        }
    }

    /**
     * 1 行を書く。行の区切りは OS によらず {@code '\n'}（{@code BufferedWriter.newLine()} は使わない。
     * どの OS で書いても同じバイト列になり、ブロックの検査値も OS によらず同じになる）
     */
    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.write('\n');
    }

    /**
     * 1ファイル分のブロックを書く。行の並びは {@link CacheFormat} のとおり。
     *
     * <p>ブロックはメモリ上で組んでから書く。記号表（S 行）は参照する行より前に置くが、記号の番号は
     * 参照する行を組みながら振るので先に書けないのと、F 行に書く検査値はブロックの残りの行が
     * 揃ってから決まるため。記号は行を書く順（R・D・O・C/U・M・A）に初めて現れた順に振る
     * （{@link SymbolTable}）。条件の表（G 行）も同じで、ガードの番号は C 行・U 行を組みながら、
     * 初めて使った順に振る（同じアトムの並びは同じ番号）。
     *
     * <p>new の証拠（{@link FileAnalysis#hints}）は行にせず、ここで呼び出し箇所に結びつけて C 行・U 行の
     * hints 列に書く（{@link #hintsByScope}）。
     */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w) throws IOException {
        if (fa.callSites.size() != fa.callSiteValues.size()) {
            // 呼び出し箇所と値は同じ位置どうしで 1 行にする。数が違えば書き手の誤り
            throw new IllegalStateException(Messages.format("analysis.cache.valuesMismatch",
                    fa.relativePath, fa.callSites.size(), fa.callSiteValues.size()));
        }
        SymbolTable symbols = new SymbolTable();
        // 記号を参照する行を、記号を振る順（R・D・O・C/U・M・A）に先に組む。
        // 読み手（CallGraphBuilder）がメソッドを ID 化する順（R → D → O → C/U）もこれと同じ
        List<String> returns = new ArrayList<>(fa.returns.size());
        for (ReturnFact r : fa.returns) {
            returns.add(r.toRow(symbols));
        }
        List<String> declarations = new ArrayList<>(fa.declarations.size());
        for (MethodDeclFact d : fa.declarations) {
            declarations.add(d.toRow(symbols));
        }
        List<String> overrides = new ArrayList<>(fa.overrides.size());
        for (OverrideFact o : fa.overrides) {
            overrides.add(o.toRow(symbols));
        }
        // 呼び出し箇所（解決できたものも失敗したものも）はソース上の順のまま書く。
        // 読み手が import 推定の候補をエッジにしたとき、元の呼び出しの並びが保たれる。
        // 値（レシーバ・実引数・ガードの番号・new の証拠）は同じ位置の callSiteValues から作り、同じ行の末尾に書く
        Map<List<Guard.Atom>, Integer> guardIds = new HashMap<>();
        List<String> guards = new ArrayList<>();
        Map<String, String> hints = hintsByScope(fa);
        List<String> calls = new ArrayList<>(fa.callSites.size());
        for (int i = 0; i < fa.callSites.size(); i++) {
            CallSite site = fa.callSites.get(i);
            CallSiteValues v = fa.callSiteValues.get(i);
            int guard = guardIdOf(v.guard(), guardIds, guards);
            String hint = (site.caller() == null || v.recvKey().isEmpty())
                    ? "" : hints.getOrDefault(scopeOf(site.caller().key(), v.recvKey()), "");
            calls.add(site.toRow(symbols, new CallSiteValues.Row(v.recv(), v.args(), guard, hint)));
        }
        List<String> functionals = new ArrayList<>(fa.functionalImpls.size());
        for (FunctionalImplFact m : fa.functionalImpls) {
            functionals.add(m.toRow(symbols));
        }
        List<String> accesses = new ArrayList<>(fa.fieldAccesses.size());
        for (FieldAccessFact a : fa.fieldAccesses) {
            accesses.add(a.toRow(symbols));
        }

        List<String> body = new ArrayList<>();
        // I行はF行の直後に置く（差分更新で、ブロックを読み進める前に依存を判定するため）。
        // 依存・解決できなかった名前・自分の宣言の指紋の 3 列
        body.add(CacheFormat.joinRow("I", String.join(",", dependenciesOf(fa)), unresolvedNamesOf(fa),
                declarationsDigestOf(fa)));
        body.addAll(symbols.rows());
        // 値グラフ（N行）は番号順。参照する行（G・R・C/U・J 行）より前にあれば、読み手は 1 回で取り込める
        for (ValueNode n : fa.valueNodes) {
            body.add(n.toRow());
        }
        // 条件の表（G 行）は N 行の直後。subject はノードを指し、C 行・U 行はガードの番号で指す
        body.addAll(guards);
        // return は全部書く（追跡できないものも -1 として。読み手には U）。
        // 「追跡できない return が1つでもあれば戻り値は不定」という判定は読み手が行う。
        // D 行より前に置く（読み手はここでメソッドを ID 化する。以前の形式と同じ順にするため）
        body.addAll(returns);
        for (TypeFact t : fa.types) {
            body.add(t.toRow());
        }
        body.addAll(declarations);
        // O行はD行の直後。読み手は宣言をID化してから上書き関係を引くので、この順でなければならない
        body.addAll(overrides);
        for (FieldDeclFact v : fa.fieldDecls) {
            body.add(v.toRow());
        }
        body.addAll(calls);
        body.addAll(functionals);
        body.addAll(accesses);
        // K行は指紋の順に並べる。同じソースならいつ解析しても同じ並びになり、
        // 旧キャッシュとの突き合わせ（宣言の連鎖）が並び順に振り回されない
        body.addAll(sortedConstantRows(fa));
        // フィールドへの代入は、同じブロックの V 行（フィールド宣言）と組で判定する（読み手はブロックの終わりで渡す）
        for (FieldAssignFact j : fa.fieldAssigns) {
            body.add(j.toRow());
        }

        // 検査値は F 行（crc 列を空にした形）から始める。F 行の件数も守る（CacheFormat の「ブロックの検査値」）
        BlockChecksum checksum = new BlockChecksum();
        checksum.addWithoutLastColumn(CacheFormat.fileRow(fa.relativePath, fa.size, fa.errors, fa.hash,
                fa.syntaxErrors, fa.unresolvedCount(), ""));
        for (String line : body) {
            checksum.add(line);
        }
        writeLine(w, CacheFormat.fileRow(fa.relativePath, fa.size, fa.errors, fa.hash, fa.syntaxErrors,
                fa.unresolvedCount(), checksum.hex()));
        for (String line : body) {
            writeLine(w, line);
        }
    }

    /**
     * 条件（アトムの並び）のガード番号。無ければ -1。初めて出てきた並びなら番号を振り、G 行を足す
     * （番号は 0 から詰めて、初めて使った順。1 つのガードのアトムは同じ番号で続けて並ぶ）
     */
    private static int guardIdOf(List<Guard.Atom> atoms, Map<List<Guard.Atom>, Integer> ids, List<String> rows) {
        if (atoms.isEmpty()) {
            return -1;
        }
        Integer known = ids.get(atoms);
        if (known != null) {
            return known;
        }
        int id = ids.size();
        ids.put(atoms, id);
        for (Guard.Atom atom : atoms) {
            rows.add(atom.toRow(id));
        }
        return id;
    }

    /**
     * new の証拠を「呼び出し元＋変数のキー」（{@link #scopeOf}）でまとめ、型の FQN をカンマ区切りにしたもの。
     *
     * <p>以前は X 行として書き、読み手がキャッシュ全体から同じ鍵で集めていた。変数のキー（バインディングキー）は
     * そのファイルの宣言を指すので、結びつけは同じファイルの中で閉じる。1 つだけ違うのは、同じメソッドキーが
     * 2 つのファイルにある（同じクラスが重複している）場合で、以前は両方のファイルの証拠が混ざっていたが、
     * 今はそれぞれのファイルの証拠がそれぞれのファイルの呼び出し箇所にだけ付く（その方が正しい）
     */
    private static Map<String, String> hintsByScope(FileAnalysis fa) {
        if (fa.hints.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> types = new LinkedHashMap<>();
        for (HintFact h : fa.hints) {
            if (HintFact.KIND_NEW.equals(h.kind())) {
                types.computeIfAbsent(scopeOf(h.callerKey(), h.scopeKey()), k -> new LinkedHashSet<>())
                        .add(h.value());
            }
        }
        Map<String, String> joined = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : types.entrySet()) {
            joined.put(e.getKey(), String.join(",", e.getValue()));
        }
        return joined;
    }

    /** 証拠を引く鍵（呼び出し元のメソッドキーと変数のキー） */
    private static String scopeOf(String callerKey, String variableKey) {
        return callerKey + '\u0000' + variableKey;
    }

    /** K行を指紋の順に並べたもの */
    private static List<String> sortedConstantRows(FileAnalysis fa) {
        List<ConstantFact> sorted = new ArrayList<>(fa.constants);
        sorted.sort(Comparator.comparing(ConstantFact::fingerprint));
        List<String> rows = new ArrayList<>(sorted.size());
        for (ConstantFact k : sorted) {
            rows.add(k.toRow());
        }
        return rows;
    }

    /**
     * 自分の宣言の指紋（I 行の 3 列目）。宣言の鍵と修飾子（{@link FileAnalysis#declarationKeys}）と、宣言している
     * 定数の値（K 行の指紋）を並べてハッシュにしたもの。どちらも無ければ空文字。
     *
     * 旧キャッシュの同じ列（{@link #oldDeclarations}）と突き合わせて、「宣言か定数の値が変わったか」だけを見る
     * （{@link Cascade#WHEN_DECLARATIONS_CHANGED}）。中身をヒープに持たないよう、ファイルごとに
     * ハッシュ1つ（16文字）だけ覚える
     */
    private static String declarationsDigestOf(FileAnalysis fa) {
        List<String> lines = new ArrayList<>(fa.declarationKeys.size() + fa.constants.size());
        lines.addAll(fa.declarationKeys);
        for (ConstantFact k : fa.constants) {
            lines.add("K " + k.fingerprint());
        }
        return digestOf(lines);
    }

    private static String digestOf(List<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return "";
        }
        Collections.sort(fingerprints);
        StringBuilder sb = new StringBuilder();
        for (String f : fingerprints) {
            sb.append(f).append('\n');
        }
        return FileHash.ofText(sb.toString());
    }

    /**
     * I 行の 2 列目。型解決に失敗したブロック（エラーがある、または理由が BINDING_FAILED の U 行がある）なら、
     * エラーの引数に現れた名前のカンマ区切り（{@link FileAnalysis#unresolvedNames}）。名前を 1 つも拾えなければ
     * {@link CacheFormat#ANY_NAME}（どの新しい型でも解析し直す）。失敗していないブロックは空文字
     */
    private static String unresolvedNamesOf(FileAnalysis fa) {
        if (!fa.resolutionFailed()) {
            return "";
        }
        return fa.unresolvedNames.isEmpty() ? CacheFormat.ANY_NAME : String.join(",", fa.unresolvedNames);
    }

    /**
     * I行の内容。バインディング解決で参照した型と import の型から、
     * 自分が宣言する型を除いたもの（自分の変更は F 行の同一性で検知できる）
     */
    private static List<String> dependenciesOf(FileAnalysis fa) {
        Set<String> own = new HashSet<>();
        for (TypeFact t : fa.types) {
            own.add(t.typeFqn());
        }
        TreeSet<String> deps = new TreeSet<>();
        for (String t : fa.referencedTypes) {
            if (!own.contains(t)) {
                deps.add(t);
            }
        }
        for (String t : fa.imports) {
            if (!own.contains(t)) {
                deps.add(t);
            }
        }
        return new ArrayList<>(deps);
    }
}
