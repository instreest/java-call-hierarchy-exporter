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
 * 規模に比例して遅くなるため）。全件解析と差分更新とでは一緒に JDT に渡すファイルが違うので、事実がバッチの組み方に
 * 依らないようにしてある（事実は解析するファイルをすべて JDT が解決し終えてから集める・名前の違うファイルで宣言した型を
 * 持つファイルはどのバッチにも添える・module-info.java は別のバッチ・JDT が止まったら関わるファイルを添えて解析し直す。
 * {@link CallEdgeExtractor#analyzeBatch}。docs/cache-design.md の「JDT に一緒に渡すファイル（バッチ）」）。
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
 *           宣言の連鎖のために、有効なブロックの自分の宣言の指紋（I 行の指紋の列）と、今のソースにあるファイルの
 *           ブロックの型階層の指紋（下記「型階層が変わったとき」）もここで覚える。
 *           今のソースに無いファイルと同じコンパイル単位の名前のファイルも有効から外す
 *           （{@link SameUnitFiles#pairedWithDeleted}）。
 *           旧キャッシュを行として読むのはこの 1 回だけ。有効なブロックの依存（I 行）は一時ファイル
 *           （依存の索引）に書き、ファイル上の範囲と F 行の件数は配列に覚えておく（パス3・パス5 が使う）。
 *   （何も変わっていなければ、ここで終わる。下記「何も変わっていないとき」）
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。H 行の親型は部分型の索引に足す。
 *           前回どのブロックも宣言していなかったトップレベルの型のパッケージは「新しい型ができたパッケージ」としても
 *           覚える（下記「新しい型」）。解析したファイルの型階層が旧キャッシュと違えば、残りをすべて解析して
 *           パス3〜5 を飛ばす（下記「型階層が変わったとき」）。
 *   パス3 … 依存の索引を読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」または「変わったパッケージ」（変わった jar のパッケージと、解析に失敗したファイルの
 *           中身の分からないパッケージ）に触れるものを再解析に回す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるため。
 *           あわせて、型解決に失敗していたブロックすべて（下記「型解決に失敗していたファイル」）と、新しい型に名前を
 *           隠されうるブロック（同じパッケージ・そのパッケージのオンデマンド import）と、I 行の型の名前の頭の部分が
 *           変わった型に当たるブロック（と、型と同じ名前のパッケージができた・無くなった親のパッケージのブロック）も回す
 *           （下記「新しい型」）。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。そのファイルの自分の宣言の指紋（宣言と定数の値）が
 *           旧キャッシュと違っていたら、または旧キャッシュで変わった jar か中身の分からないパッケージに触れていたなら、
 *           または sealed な型かアノテーション型を宣言しているなら、宣言する型を「変わった型」に加えて
 *           パス3へ戻る（下記「宣言の連鎖」）。「変わった型」は、どの時点でも部分型で閉じている（下記「親型の連鎖」）。
 *           「変わった型」が増えなくなるまで繰り返す。型階層が違ったファイルがあれば、残りをすべて解析して止める。
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
 * A を呼ぶ側のオーバーロードの選び方・式の型が変わる。そこで、書き手は I 行の最後の列に自分の宣言の指紋（宣言する型・
 * メソッド・フィールドの JDT のバインディングの鍵と修飾子など（親型・型引数の上限・関数型も）と、K 行の指紋。中身は
 * {@link jche.cache.FileAnalysis#declarationKeys} と TypeContextTracker#recordDeclarations）を
 * 書き、パス4 で解析し直した結果がこれと違えば、そのファイルが宣言する型も「変わった型」に加えてパス3からやり直す
 * （docs/cache-unification-qa.md の Q83）。継承したものは入れない（親の変化は「親型の連鎖」で届く）。
 * 旧キャッシュの I 行（か解決できなかった名前）が変わった jar のパッケージ（か、解析に失敗したファイルの中身の分からない
 * パッケージ）に触れていたファイルの型は、指紋に関わらず、
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
 * ことも）は H 行に無いので、書き手は名前にした型の頭（推移的な親型を型引数ごと・型引数の上限・関数型。
 * BindingNames#noteHeaderTypes。Q86）を I 行に載せ、差分更新は I 行が変わった jar に触れていたファイルの型を
 * 変わった型にする（上の「宣言の連鎖」）。H 行は消去した親しか持たないので、親型の型引数の変化もこの I 行で拾う。
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
 * 足した）は、次の 3 通りで、I 行に触れずに他のファイルの解決結果を変える。どれも<b>型かパッケージの単位</b>で当て、
 * 名前の一部（エラーの引数に現れた名前・単純名）は照合しない（docs/cache-unification-qa.md の Q131。以前は解決できなかった
 * 名前を I 行に書いて新しい型の名前と照合していたが、何を区切りにするか・何を拾うか（Q42・Q53・Q64・Q79・Q80・Q87・
 * Q91〜Q96）の入れ忘れがそのまま静かな取りこぼしになった）。
 * <ul>
 *   <li>無い型の名前（{@code Foo.run()}）には JDT がバインディングを返さないので、参照した側の依存には
 *       何も残らない。そのファイルは F 行のエラー数か U 行の BINDING_FAILED として型解決に失敗している。
 *       <b>型解決に失敗していたブロックは、何かが変わった実行では必ず解析し直す</b>（下の「型解決に失敗していたファイル」）</li>
 *   <li>同じパッケージに足したトップレベルの型は、オンデマンド import（{@code import q.*}）と {@code java.lang} の型を
 *       隠す（JLS 6.4.1）。自分のパッケージは I 行に無いので、新しい型のパッケージと同じパッケージのブロックと、
 *       そのパッケージをオンデマンド import するブロックのうち、I 行に何かあるものを解析し直す
 *       （{@link StaleTypes#touches}。どの名前が隠されるかは見ない）。型を 1 つも宣言しないブロック
 *       （{@code package-info.java}）は自分のパッケージが分からないので、どのパッケージの新しい型にも当てる</li>
 *   <li>パッケージと同じ名前の型（パッケージ {@code a.b} があるのに足したパッケージ {@code a} のクラス {@code b}）は、
 *       {@code a.b.C} の解決を変える（JLS 6.5.2・7.1）。I 行の型の名前の頭の部分が変わった型に当たるブロックも
 *       解析し直す（{@code StaleTypes#underChangedType}。Q87）。逆に、型 {@code a.b} があるところにパッケージ {@code a.b} が
 *       できた・無くなった（jar のパッケージが変わった）ときは、型のファイルの「パッケージと衝突する」エラーが出る・消える。
 *       このエラーはバッチに依らない（JDT はフォルダと jar でパッケージがあるかを決める）ので、親のパッケージ {@code a}
 *       のブロックを解析し直す（{@link StaleTypes#collidesWithChangedPackage}）。オンデマンド import（{@code import a.*}）の
 *       パッケージができた・無くなったときも、その import を持つブロックを解析し直す（{@code a} が下のパッケージだけで
 *       できていると、最後の下のパッケージが無くなったときに import のエラーになる）。パッケージがあるかは、H 行の
 *       パッケージとファイルの置き場所のフォルダで数える（JDT はフォルダで決める）</li>
 * </ul>
 * 「前回は無かった」は、パス1 を読み終えたときの「変わった型」（無効になったブロックが宣言していた型と、
 * その部分型）と、有効なブロックが宣言していた型のどちらにも無いことで見る（{@link StaleTypes#declaredInValidBlock}。
 * 同じ名前のファイルの組として有効なブロックのファイルが後からパス2 に回っても、その型は新しい型ではない。以前は
 * 無効なブロックの型だけで見ていたので、隠蔽の規則をパッケージ単位に粗くしたときに、ソースフォルダを足すだけで
 * そのパッケージのブロックをすべて解析し直していた）。
 *
 * 依存 jar の変更も同じ仕組みで扱う。jar の中の型は解析し直せない（ソースが無い）ので、
 * 「その jar のパッケージの型を参照しているファイル」を再解析の対象にする。
 * 型ではなくパッケージで見るのは、jar の版を差し替えたときに旧版にだけあった型を
 * 新しい jar からは知れないためで、L 行にパッケージ一覧を残すのは jar が削除された後にも
 * 影響範囲を知るため（{@link LibraryDiff}）。jar の型が自分と同じパッケージにできたときは、新しい型と同じく
 * オンデマンド import・{@code java.lang} の型・完全修飾名の頭（{@code a.b.C} の {@code a}）を隠しうるので、自分の
 * パッケージが変わった jar のパッケージにあるブロックは、I 行に何かあれば解析し直す（Q54。{@link StaleTypes#touchesLibrary}）。
 * 型のメンバーを持ち込む import（{@code import static org.lib.K.*}・
 * {@code import org.lib.Outer.*}）は I 行に {@code org.lib.K.*} と載るので、頭の部分が変わった jar のパッケージかでも当てる。
 * jar の無名パッケージのクラスは {@link LibraryFact#UNNAMED_PACKAGE} というパッケージとして扱い、点の無い型の名前が当たる。
 *
 * <h2>型解決に失敗していたファイル</h2>
 * 型解決に失敗していたブロック（F 行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）は、無い型・見えない型の
 * 名前を依存に残せないので、何が変われば解けるかを I 行からは決められない。そこで、何かが変わった実行（変わったファイル・
 * jar・できた／無くなったパッケージ・中身の分からないパッケージのどれかがある。{@link StaleTypes#isEmpty}）では、
 * <b>名前を照合せず必ず解析し直す</b>（パス3 の最初）。連鎖させるか（解析し直した型を変わった型にするか）は、ほかの
 * 解析し直したファイルと同じ決まり（宣言の指紋・jar に触れていた・sealed かアノテーション型）で決める。
 * 何も変わっていない実行では解析し直さない（旧キャッシュをそのまま残す）。
 * 費用は失敗しているファイルの数に比例する。コンパイルエラーが常態のプロジェクト（生成ソースを置かずに解析する・Lombok）では
 * 毎回それらのファイルを解析し直すことになるので、warnings.txt の「コンパイルエラーがある」の項目で、ビルドしてから
 * 解析するよう案内する。Doma のように生成物をソースが名指さないフレームワークでは、生成物が無くてもエラーにならない
 * （{@code test/incremental} の Doma のケース）。
 *
 * <h2>型階層が変わったとき（全件解析に切り替える）</h2>
 * 解析し直したファイル（パス2 の変わったファイル・パス4 の依存するファイル）の型階層（H 行の、名前で参照できる型の集合・
 * 親型・親クラスの連鎖・継承した実装。{@link BlockWriter#hierarchyDigestOf}）が旧キャッシュのブロックと違えば、
 * 依存で選ぶのをやめて、まだ有効なブロックのファイルをすべて解析し直す（{@link #analyzeAllIfHierarchyChanged}）。
 * 型階層は選択（jche.graph.MethodSelection。どの本体が動くか）の材料で、階層の変化がどのファイルの事実に効くかを
 * I 行と部分型の索引から漏れなく決められる保証は無い（親型の連鎖と型の頭の規則がそれを担っているが、入れ忘れは静かな
 * 食い違いになる）。階層を変える編集（親の付け替え・インターフェースの抽出・入れ子の型の追加）は本体の編集より少ないので、
 * その実行だけ全件になる費用を、安全網として受け入れる。無名クラス・ローカルクラス（{@code Main$1}）はほかのファイルから
 * 名前で参照できないので階層に数えない（本体にラムダや無名クラスを足しただけで全件にならない）。新しいファイルには
 * 比べる相手が無いので、足しただけでは切り替わらない（新しい型の決まりで届く）。型解決に失敗していたブロック・
 * 失敗したファイルも比べない（エラーのあるファイルの型は JDT の回復で揺れる。同じ名前の型の組の 2 つ目は「型が重複している」
 * エラーになり、組の相手の有無で H 行が変わる。それらは名前に依らず解析し直し、宣言の指紋で連鎖する）。
 *
 * <h2>解析に失敗したファイル（中身の分からないパッケージ）</h2>
 * 解析に失敗したファイル（JDT のスタックが溢れた・JDT が止まり、脇に置いて 1 つだけで解析し直しても止まった。
 * Q62 と {@link CallEdgeExtractor#analyzeBatch}）は事実を書けないが、その型は JDT がソースパスから読むので、
 * ほかのファイルの解決には効いている。どの型を宣言しているかも分からないので、そのファイルの置き場所のパッケージを
 * 「中身の分からないパッケージ」にして、変わった jar のパッケージと同じ決まりで、そのパッケージの型を使うファイルを
 * 解析し直す（{@link StaleTypes#addOpaque}）。そのファイルには印のブロック（F 行と空の I 行。内容ハッシュは空）を書き、
 * 次の実行も必ず解析し直す（失敗が続くあいだは、実行のたびにそのパッケージの型を使うファイルも解析し直す）。消したときは、
 * 型を宣言していなかった無効なブロックとして、パス1 で同じくそのパッケージを中身の分からないパッケージにする。
 *
 * <h2>部品（同じパッケージの package-private なクラス）</h2>
 * <pre>
 *   {@link StaleTypes}          「変わった型」とパッケージの集合。どのブロックを解析し直すかの判定（パス1〜4）
 *   {@link ReanalysisReason}    解析し直す理由（集計の内訳）
 *   {@link ReanalysisCascade}   解析し直したファイルの型を「変わった型」に加えるか（宣言の連鎖）
 *   {@link BlockWriter}         解析結果を 1 ブロックとして書く（行の並び・記号表・I 行・検査値。パス2〜4 の受け手）
 *   {@link OldBlock}            パス1 で読んでいる途中の旧キャッシュのブロック 1 つ
 *   {@link OldCache}            パス1 で読んだ有効なブロックの範囲と件数（パス5 がバイトのまま書き写す）
 *   {@link DepsIndex}           パス1 が書き、パス3 が読む依存の索引（一時ファイル）
 *   {@link SameUnitFiles}       同じコンパイル単位の名前のファイルの組
 *   {@link LibraryDiff}         依存 jar・クラスフォルダの指紋と変化（パス0）
 *   {@link CallEdgeExtractor}   JDT に渡すバッチの組み方と、事実の収集（{@link FactVisitor}）
 * </pre>
 * このクラス自身が持つのは、パスの順序と、旧キャッシュを読む・書き写す・差し替える手順である。
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
     * 相対パス -> 旧キャッシュの自分の宣言の指紋（I 行の指紋の列。有効なブロックのぶん）。
     * パス4で解析し直した結果と突き合わせて、宣言と定数の値が変わったかだけを見る（「宣言の連鎖」）。
     * ファイルごとに 16 文字のハッシュ1つなので、ヒープに載せても軽い
     */
    private final Map<String, String> oldDeclarations = new HashMap<>();
    /**
     * 相対パス -> 旧キャッシュの型階層の指紋（{@link BlockWriter#hierarchyDigestOf}。今のソースにあるファイルの
     * ブロックすべて。有効でないものも、変わったファイルを解析した結果と比べるために持つ）。
     * 解析し直した結果と違えば、差分更新をやめて残りを全件解析する（クラスの説明「型階層が変わったとき」）
     */
    private final Map<String, String> oldHierarchy = new HashMap<>();
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
        // ソースの全体を構文だけで読み、どのバッチにも添えるファイルを決める（CallEdgeExtractor#prepare）。
        // 解析するファイルが少ない差分更新でも全体を読む。添えるファイルと下の警告がソースの中身だけで決まり、
        // 全件解析と同じになるようにするため
        ProjectScan scan = extractor.prepare(new ArrayList<>(live.values()));
        if (!scan.context.isEmpty()) {
            Log.info(Messages.format("analysis.contextFiles", scan.context.size()));
        }
        scan.warnPackageMismatches();

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
            OldCache old = oldCacheUsable ? scanOldCache(live, valid, stale, deps) : null;
            if (old == null) {
                // 途中で切れている・読めないキャッシュ。中途半端に再利用すると呼び出しが静かに欠けるので、
                // 丸ごと捨てて全件解析し直す（ヘッダが違ったときと同じ扱い）
                valid.clear();
                oldDeclarations.clear();
                oldHierarchy.clear();
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
            for (Map.Entry<String, SourceFile> en : live.entrySet()) {
                if (!valid.contains(en.getKey())) {
                    changed.add(en.getValue());
                }
            }
            // 同じクラスが 2 つのソースフォルダにあるとき、その組は必ず一緒に解析する（SameUnitFiles）
            units.pullInto(changed, new ArrayList<>(), valid, live);

            if (canKeepAsIs(old, changed, stale, head)) {
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
                        oldHierarchy, changedDuringRun, parsedThisRun,
                        f -> StaleTypes.packageOfUnit(layout.unitNameOf(f.path())));

                // --- パス2: 変更・追加されたファイルを解析 ---
                writer.stale = stale;
                // ファイル自身が変わっているので、宣言する型は無条件に「変わった型」へ（改名・追加に備える）
                writer.cascade = ReanalysisCascade.ALWAYS;
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

                // --- パス3・パス4: 依存で無効になったファイルを解析し直す（宣言が変わると連鎖するので不動点まで） ---
                writer.cascade = ReanalysisCascade.WHEN_DECLARATIONS_CHANGED;
                if (old != null && !valid.isEmpty()) {
                    if (!analyzeAllIfHierarchyChanged(extractor, writer, live, valid)) {
                        reanalyzeDependents(extractor, writer, live, valid, stale, deps, old);
                    }

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
    private boolean canKeepAsIs(OldCache old, List<SourceFile> changed, StaleTypes stale, List<String> head)
            throws IOException {
        if (old == null || !old.allKept || !old.writtenAsIs || !changed.isEmpty() || !stale.isEmpty()) {
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
     * 型階層が変わったら、差分更新をやめて残りをすべて解析し直す（クラスの説明「型階層が変わったとき」）。
     * 解析し直したファイル（パス2・パス4）のどれかの型階層（H 行の型の集合・親型・親クラスの連鎖・継承した実装）が
     * 旧キャッシュと違えば（{@link BlockWriter#hierarchyChanged}）、まだ有効なブロックのファイルをすべて
     * ソースの一覧の順に解析し、有効なブロックを無くす（パス5 は何も書き写さない）。
     *
     * @return 全件解析に切り替えたか
     */
    private boolean analyzeAllIfHierarchyChanged(CallEdgeExtractor extractor, BlockWriter writer,
                                                 Map<String, SourceFile> live, Set<String> valid) throws IOException {
        String changedFile = writer.hierarchyChanged();
        if (changedFile == null || valid.isEmpty()) {
            return changedFile != null;
        }
        Log.info(Messages.format("analysis.cache.hierarchyChanged", changedFile, valid.size()));
        List<SourceFile> rest = new ArrayList<>();
        for (SourceFile f : live.values()) {
            if (valid.contains(f.relativePath())) {
                rest.add(f);
            }
        }
        valid.clear();
        writer.countAs = ReanalysisReason.BY_SOURCE;
        analyzeInBatches(extractor, rest, writer);
        return true;
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
     * 数え方は解析したときと同じにする（{@link ReanalysisCascade#ALWAYS} 相当）。
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
     * <p>宣言の連鎖のために、有効なブロックの自分の宣言の指紋（I 行の指紋の列）もここで覚える
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
     * @param deps 有効なブロックの依存を書く索引（依存の無いブロックは書かない）
     * @return 旧キャッシュをそのまま使ってよければ、書き写す候補。途中で切れている・読めないなら null
     */
    private OldCache scanOldCache(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale, DepsIndex deps) {
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
                                old, deps);
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
                        block.declarations = CacheFormat.columnAt(cols, 2);
                    }
                }
                in.addTo(block.checksum);
                if (rowType == CacheFormat.ROW_TYPE) {
                    block.typeRows.add(in.line());
                } else if (!block.bindingFailed && rowType == CacheFormat.ROW_UNRESOLVED
                        && UnresolvedCallFact.BINDING_FAILED.equals(
                                UnresolvedCallFact.reasonColumn(in.columns()))) {
                    // エラーとしては報告されなかったが呼び出し先が解決できなかった。何かが変われば解析し直す
                    block.bindingFailed = true;
                }
            }
            if (block != null) {
                damaged += finishOldBlock(block, in.nextLineStart(), in.irregularities(), live, valid, stale,
                        old, deps);
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
     *   <li>検査値が合い、サイズと内容ハッシュも一致する … 有効。書き写す候補（{@code old}）に積み、
     *       依存（I 行）を索引（{@code deps}）に書く。型解決に失敗していたブロックもここに入る
     *       （何かが変わった実行ではパス3 で必ず解析し直す。{@link #reanalyzeDependents}）</li>
     *   <li>それ以外 … 無効。宣言していた型（H 行）を「変わった型」に加える
     *       （改名・削除された型を参照していたファイルを解析し直すため）</li>
     * </ul>
     * どちらでも、今のソースにあるファイルのブロックなら型階層の指紋を覚える（{@link #oldHierarchy}）。
     *
     * @param end              ブロックの終わり（次の F 行・Z 行の先頭）のファイル上の位置
     * @param irregularAtEnd   そのときの {@link CacheReader#irregularities}
     * @return 検査値が合わず、そのファイルを解析し直すブロックなら 1（ログの件数に数える）。それ以外は 0
     */
    private int finishOldBlock(OldBlock block, long end, long irregularAtEnd, Map<String, SourceFile> live,
                               Set<String> valid, StaleTypes stale, OldCache old, DepsIndex deps) {
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
        if (block.inSources && intact && block.errors == 0 && !block.bindingFailed) {
            // 変わったファイルも解析し直すファイルも、解析した結果の型階層をこれと比べる（BlockWriter#hierarchyChanged）。
            // 壊れたブロックと型解決に失敗していたブロックの H 行は比べない（エラーのあるファイルの型は JDT の回復で揺れる。
            // 同じ名前の型の組の 2 つ目など。失敗していたブロックは名前に依らず解析し直し、宣言の指紋で連鎖する）
            oldHierarchy.put(live.get(block.rel).relativePath(), BlockWriter.hierarchyDigestOf(declared));
        }
        if (intact && block.identical) {
            // 有効なブロックは今のソースにある（isValidBlock）。パスは今のソース一覧の文字列を使い回す
            String rel = live.get(block.rel).relativePath();
            valid.add(rel);
            if (!block.declarations.isEmpty()) {
                oldDeclarations.put(rel, block.declarations);
            }
            for (TypeFact t : declared) {
                stale.packageNow(t.pkg());
                stale.declaredInValidBlock(t.typeFqn());
            }
            old.add(rel, block.start, end, block.errors, block.syntaxErrors, block.unresolved,
                    irregularAtEnd != block.irregularAtStart, block.errors > 0 || block.bindingFailed,
                    declared.isEmpty() ? null : declared.get(0).pkg());
            if (!block.deps.isEmpty()) {
                deps.add(old.size - 1, block.deps);
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
    static boolean declaresNoType(String relativePath) {
        return relativePath.endsWith("package-info.java") || relativePath.endsWith("module-info.java");
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
                    ReanalysisReason collides = stale.collidesWithChangedPackage(old.packages[i]);
                    if (collides != ReanalysisReason.UNTOUCHED && valid.remove(old.paths[i])) {
                        (collides == ReanalysisReason.BY_SOURCE ? dependents : libraryDependents).add(old.paths[i]);
                    }
                }
                first = false;
            }
            // 型解決に失敗していたブロックは、名前を照合せず必ず解析し直す（クラスの説明「型解決に失敗していたファイル」）。
            // 無い型・見えない型の名前は依存（I 行の型）に残らないので、依存では見つけられない。ここに来るのは何かが
            // 変わった実行だけ（isEmpty なら上で返している）。連鎖させるか（jarDriven）は I 行から決める（下の select）
            for (int i = old.unresolvedTypes.nextSetBit(0); i >= 0; i = old.unresolvedTypes.nextSetBit(i + 1)) {
                if (valid.remove(old.paths[i])) {
                    (stale.hasSourceChanges() ? dependents : libraryDependents).add(old.paths[i]);
                }
            }
            DepsIndex.Consumer select = (index, depsCsv) -> {
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
                ReanalysisReason touched = stale.touches(depsCsv, old.packages[index]);
                if (touched == ReanalysisReason.BY_SOURCE) {
                    valid.remove(rel);
                    dependents.add(rel);
                } else if (touched == ReanalysisReason.BY_LIBRARY) {
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
            writer.countAs = ReanalysisReason.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = ReanalysisReason.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
            if (analyzeAllIfHierarchyChanged(extractor, writer, live, valid)) {
                return;   // 解析し直したファイルの型階層が変わった。残りは全件解析した
            }
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
    private void selectDependentsFromCache(OldCache old, DepsIndex.Consumer select) throws IOException {
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
    static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.write('\n');
    }

}
