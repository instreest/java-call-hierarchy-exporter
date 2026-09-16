// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.external;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * classファイルを読み、参照しているメソッドを「どのメソッドの何行目から」まで含めて列挙する。
 *
 * 定数プールに加えて各メソッドの命令列（Code 属性）を歩き、{@code invoke*} 命令が指す
 * 参照先を、その命令のオフセットに対応するソース行（LineNumberTable 属性）と一緒に拾う。
 * ラムダ式・メソッド参照（{@code invokedynamic}）は BootstrapMethods 属性の引数にある
 * MethodHandle を辿って、実際に指しているメソッドを同じ呼び出し箇所として拾う。
 * 命令の解釈（スタックの中身や値の追跡）はしない。参照先とその位置だけを取り出すので、
 * ASM や BCEL といった外部ライブラリを持ち込まずに実装できる。
 *
 * <h2>行番号が取れないとき</h2>
 * {@code -g:none} でビルドされた class には LineNumberTable も SourceFile も無い。
 * その場合も呼び出し元メソッドまでは分かるので、行だけ無しで返す（{@link CallSite#line} が -1）。
 * 命令列を歩いても辿り着けなかった定数プールの参照（{@code ldc} で MethodHandle 定数を
 * 積む形など、実在の配布物ではまず現れない）は {@link #unlocatedRefs} に残し、呼び出し側が
 * クラス単位の粒度で出せるようにする。呼び出しを静かに落とさないため。
 */
final class ClassFileRefs {

    /** 参照先メソッド1件 */
    record MethodEntry(String ownerFqn, String name, String paramSig) {
    }

    /**
     * 呼び出し箇所1件。
     *
     * @param callee       参照先
     * @param callerMethod 呼び出し元メソッド名（{@code <init>} / {@code <clinit>} を含む。
     *                     ラムダ本体の合成メソッド {@code lambda$run$0} は囲みメソッド名 {@code run} に
     *                     読み替える。ソース解析側が「ラムダ内の呼び出しは囲みメソッドに計上する」のと揃えるため）
     * @param line         呼び出し元のソース行。LineNumberTable が無ければ -1
     */
    record CallSite(MethodEntry callee, String callerMethod, int line) {
    }

    /** 参照している自分のクラス名（FQN。内部クラスは {@code Outer$Inner} のバイナリ名） */
    final String thisClass;
    /** SourceFile 属性のファイル名（{@code NightJob.java}）。無ければ null */
    String sourceFile;
    /** 命令列から見つけた呼び出し箇所。メソッドの宣言順 → 命令のオフセット順 */
    final List<CallSite> callSites = new ArrayList<>();
    /** 定数プールにはあるが、どの命令からも辿れなかった参照先（クラス単位の粒度でしか言えないもの） */
    final List<MethodEntry> unlocatedRefs = new ArrayList<>();

    // --- 定数プール（parse の間だけ使う） ---
    private final int count;
    private final int[] tags;
    private final String[] utf8;
    private final int[] refA;
    private final int[] refB;
    /** 定数プールの Methodref / InterfaceMethodref のうち、命令列から辿れたもの */
    private final boolean[] located;

    /**
     * {@code invokedynamic} の呼び出し箇所。参照先は BootstrapMethods 属性（class の末尾）を読むまで
     * 分からないので、命令列を歩いた時点では {@link #callSites} に callee が null の仮の項目を置き、
     * ここに bootstrap の番号を控えておく。最後に仮の項目をその場で参照先に置き換える（並びを保つため）
     */
    private final List<Integer> indyBootstrap = new ArrayList<>();
    private int[][] bootstrapArgs;   // BootstrapMethods 属性。[i] = i 番目の bootstrap の引数の定数プール索引

    private ClassFileRefs(String thisClass, int count, int[] tags, String[] utf8, int[] refA, int[] refB) {
        this.thisClass = thisClass;
        this.count = count;
        this.tags = tags;
        this.utf8 = utf8;
        this.refA = refA;
        this.refB = refB;
        this.located = new boolean[count];
    }

    static ClassFileRefs parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("classファイルではありません");
        }
        in.readUnsignedShort();     // minor
        in.readUnsignedShort();     // major
        int count = in.readUnsignedShort();

        int[] tags = new int[count];
        String[] utf8 = new String[count];
        int[] refA = new int[count];   // Class:name_index / Ref:class_index / NameAndType:name_index
                                       // MethodHandle:reference_index / InvokeDynamic:bootstrap_method_attr_index
        int[] refB = new int[count];   // Ref:name_and_type_index / NameAndType:descriptor_index

        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();                            // Utf8
                case 7 -> refA[i] = in.readUnsignedShort();                  // Class
                case 8, 16, 19, 20 -> in.readUnsignedShort();                // String / MethodType / Module / Package
                case 15 -> {                                                 // MethodHandle
                    in.readUnsignedByte();                                   //   reference_kind
                    refA[i] = in.readUnsignedShort();                        //   reference_index（Methodref 等）
                }
                case 3, 4 -> in.readInt();                                   // Integer / Float
                case 5, 6 -> {                                               // Long / Double
                    in.readLong();
                    i++;   // 8バイト定数は2スロット占有する（ここを飛ばさないと全体がずれる）
                }
                case 9, 10, 11, 12, 17, 18 -> {   // Field/Method/InterfaceMethodref, NameAndType, Dynamic/InvokeDynamic
                    refA[i] = in.readUnsignedShort();
                    refB[i] = in.readUnsignedShort();
                }
                default -> throw new IOException("未知の定数プールタグ: " + tag);
            }
        }

        in.readUnsignedShort();                 // access_flags
        int thisClassIdx = in.readUnsignedShort();
        in.readUnsignedShort();                 // super_class
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }

        // this_class が Class 定数を指していない・名前の Utf8 が無い壊れた class でも、
        // null を持ち出さない（後段の照合で NPE になり、被参照スキャン全体が止まる）
        String thisName = (thisClassIdx > 0 && thisClassIdx < count && tags[thisClassIdx] == 7
                && refA[thisClassIdx] < count)
                ? internalToFqn(utf8[refA[thisClassIdx]]) : null;
        if (thisName == null) {
            thisName = "(不明)";
        }

        // フィールド（属性は読み飛ばす）
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();             // access_flags
            in.readUnsignedShort();             // name_index
            in.readUnsignedShort();             // descriptor_index
            skipAttributes(in);
        }

        // メソッド。Code 属性を歩いて呼び出し箇所を集める
        ClassFileRefs out = new ClassFileRefs(thisName, count, tags, utf8, refA, refB);
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();             // access_flags
            String name = out.utf8At(in.readUnsignedShort());
            in.readUnsignedShort();             // descriptor_index
            String callerMethod = enclosingMethodName(name);
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String attrName = out.utf8At(in.readUnsignedShort());
                int len = in.readInt();
                if ("Code".equals(attrName)) {
                    out.readCode(in, len, callerMethod);
                } else {
                    skipFully(in, len);
                }
            }
        }

        // class の属性。SourceFile と BootstrapMethods だけ読む
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            String attrName = out.utf8At(in.readUnsignedShort());
            int len = in.readInt();
            if ("SourceFile".equals(attrName) && len == 2) {
                out.sourceFile = out.utf8At(in.readUnsignedShort());
            } else if ("BootstrapMethods".equals(attrName)) {
                int n = in.readUnsignedShort();
                out.bootstrapArgs = new int[n][];
                for (int b = 0; b < n; b++) {
                    in.readUnsignedShort();     // bootstrap_method_ref
                    int argc = in.readUnsignedShort();
                    int[] args = new int[argc];
                    for (int k = 0; k < argc; k++) {
                        args[k] = in.readUnsignedShort();
                    }
                    out.bootstrapArgs[b] = args;
                }
            } else {
                skipFully(in, len);
            }
        }

        out.resolveIndy();
        // 命令列から辿れなかった Methodref / InterfaceMethodref
        for (int i = 1; i < count; i++) {
            if ((tags[i] == 10 || tags[i] == 11) && !out.located[i]) {
                MethodEntry e = out.methodEntryAt(i);
                if (e != null) {
                    out.unlocatedRefs.add(e);
                }
            }
        }
        return out;
    }

    // --- Code 属性 ---

    /**
     * Code 属性を読み、{@code invoke*} 命令の参照先と行番号を {@code sites} に足す。
     *
     * 命令列は各命令の長さだけを頼りに前へ進む（{@link #instructionLength}）。
     * {@code tableswitch} / {@code lookupswitch} / {@code wide} だけが可変長で、
     * それ以外はオペコードごとに固定。未知のオペコードに当たったら、その先の解釈は
     * 信用できないのでそのメソッドの走査をやめる（拾えなかった参照は unlocatedRefs に残る）。
     */
    private void readCode(DataInputStream in, int len, String callerMethod) throws IOException {
        in.readUnsignedShort();                 // max_stack
        in.readUnsignedShort();                 // max_locals
        int codeLength = in.readInt();
        byte[] code = new byte[codeLength];
        in.readFully(code);
        int exceptions = in.readUnsignedShort();
        skipFully(in, exceptions * 8);

        // LineNumberTable（複数あってもよい仕様なので全部集める）
        int[] startPc = new int[0];
        int[] lines = new int[0];
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            String attrName = utf8At(in.readUnsignedShort());
            int attrLen = in.readInt();
            if ("LineNumberTable".equals(attrName)) {
                int n = in.readUnsignedShort();
                int base = startPc.length;
                startPc = Arrays.copyOf(startPc, base + n);
                lines = Arrays.copyOf(lines, base + n);
                for (int k = 0; k < n; k++) {
                    startPc[base + k] = in.readUnsignedShort();
                    lines[base + k] = in.readUnsignedShort();
                }
            } else {
                skipFully(in, attrLen);
            }
        }
        LineTable table = new LineTable(startPc, lines);

        int pc = 0;
        while (pc < codeLength) {
            int op = code[pc] & 0xFF;
            int length = instructionLength(code, pc);
            if (length <= 0 || pc + length > codeLength) {
                return;   // 未知のオペコード、または命令が途中で切れている
            }
            switch (op) {
                case 0xB6, 0xB7, 0xB8, 0xB9 -> {   // invokevirtual / invokespecial / invokestatic / invokeinterface
                    int idx = u2(code, pc + 1);
                    MethodEntry e = methodEntryAt(idx);
                    if (e != null) {
                        located[idx] = true;
                        callSites.add(new CallSite(e, callerMethod, table.lineAt(pc)));
                    }
                }
                case 0xBA -> {                     // invokedynamic（参照先は後で埋める仮の項目）
                    int idx = u2(code, pc + 1);
                    if (idx > 0 && idx < count && tags[idx] == 18) {
                        callSites.add(new CallSite(null, callerMethod, table.lineAt(pc)));
                        indyBootstrap.add(refA[idx]);
                    }
                }
                default -> {
                }
            }
            pc += length;
        }
    }

    /**
     * {@code invokedynamic} の仮の項目を、BootstrapMethods の引数から求めた参照先に置き換える。
     * ラムダ式（LambdaMetafactory）なら引数に実装メソッドの MethodHandle があり、
     * メソッド参照 {@code Counter::bump} ならそれが自分のメソッドを指す。
     * ラムダ本体は同じクラスの合成メソッド {@code lambda$x$0} を指すので、自分の型でないぶんは
     * 呼び出し側（{@link ExternalUsageScanner}）の「自分の型か」の判定で自然に落ちる。
     * 文字列結合（StringConcatFactory）の引数には MethodHandle が無いので、その仮の項目は消えるだけ。
     */
    private void resolveIndy() {
        if (indyBootstrap.isEmpty()) {
            return;
        }
        List<CallSite> resolved = new ArrayList<>(callSites.size());
        int next = 0;
        for (CallSite s : callSites) {
            if (s.callee() != null) {
                resolved.add(s);
                continue;
            }
            int b = indyBootstrap.get(next++);
            if (bootstrapArgs == null || b < 0 || b >= bootstrapArgs.length) {
                continue;
            }
            for (int arg : bootstrapArgs[b]) {
                if (arg <= 0 || arg >= count || tags[arg] != 15) {
                    continue;   // MethodHandle 以外の引数（MethodType、文字列など）
                }
                int ref = refA[arg];
                MethodEntry e = methodEntryAt(ref);
                if (e != null) {
                    located[ref] = true;
                    resolved.add(new CallSite(e, s.callerMethod(), s.line()));
                }
            }
        }
        callSites.clear();
        callSites.addAll(resolved);
    }

    /** 定数プールの Methodref / InterfaceMethodref を {@link MethodEntry} にする。違う種別・壊れていれば null */
    private MethodEntry methodEntryAt(int i) {
        if (i <= 0 || i >= count || (tags[i] != 10 && tags[i] != 11)) {
            return null;
        }
        int classIdx = refA[i];
        int natIdx = refB[i];
        if (classIdx <= 0 || natIdx <= 0 || classIdx >= count || natIdx >= count) {
            return null;
        }
        String owner = internalToFqn(utf8At(refA[classIdx]));
        String name = utf8At(refA[natIdx]);
        String desc = utf8At(refB[natIdx]);
        if (owner == null || name == null || desc == null) {
            return null;
        }
        return new MethodEntry(owner, name, String.join(",", parseParams(desc)));
    }

    private String utf8At(int i) {
        return (i > 0 && i < count) ? utf8[i] : null;
    }

    /**
     * ラムダ本体の合成メソッド {@code lambda$run$0} を囲みメソッド名 {@code run} に読み替える。
     * それ以外はそのまま。名前が null（壊れた class）なら "?"
     */
    static String enclosingMethodName(String name) {
        if (name == null) {
            return "?";
        }
        if (name.startsWith("lambda$")) {
            int end = name.lastIndexOf('$');
            if (end > "lambda$".length()) {
                return name.substring("lambda$".length(), end);
            }
        }
        return name;
    }

    /** バイトコードのオフセット → 行番号。LineNumberTable の「開始オフセット以下で最大」の項目を引く */
    private static final class LineTable {
        private final int[] startPc;
        private final int[] lines;

        LineTable(int[] startPc, int[] lines) {
            // start_pc の昇順に並べる（仕様上は順不同）
            Integer[] order = new Integer[startPc.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Integer.compare(startPc[a], startPc[b]));
            this.startPc = new int[order.length];
            this.lines = new int[order.length];
            for (int i = 0; i < order.length; i++) {
                this.startPc[i] = startPc[order[i]];
                this.lines[i] = lines[order[i]];
            }
        }

        int lineAt(int pc) {
            int line = -1;
            for (int i = 0; i < startPc.length && startPc[i] <= pc; i++) {
                line = lines[i];
            }
            return line;
        }
    }

    // --- 命令の長さ ---

    private static int u2(byte[] code, int at) {
        return ((code[at] & 0xFF) << 8) | (code[at + 1] & 0xFF);
    }

    /**
     * オフセット pc にある命令の長さ（オペコード込み）。未知のオペコードなら -1。
     * 可変長は tableswitch / lookupswitch（4 バイト境界への詰め物と表）と wide（次の命令による）だけ
     */
    static int instructionLength(byte[] code, int pc) {
        int op = code[pc] & 0xFF;
        switch (op) {
            case 0xAA -> {   // tableswitch
                int pad = (4 - ((pc + 1) % 4)) % 4;
                int at = pc + 1 + pad;
                if (at + 12 > code.length) {
                    return -1;
                }
                int low = s4(code, at + 4);
                int high = s4(code, at + 8);
                if (high < low) {
                    return -1;
                }
                return 1 + pad + 12 + 4 * (high - low + 1);
            }
            case 0xAB -> {   // lookupswitch
                int pad = (4 - ((pc + 1) % 4)) % 4;
                int at = pc + 1 + pad;
                if (at + 8 > code.length) {
                    return -1;
                }
                int npairs = s4(code, at + 4);
                if (npairs < 0) {
                    return -1;
                }
                return 1 + pad + 8 + 8 * npairs;
            }
            case 0xC4 -> {   // wide
                if (pc + 1 >= code.length) {
                    return -1;
                }
                return ((code[pc + 1] & 0xFF) == 0x84) ? 6 : 4;   // wide iinc は 6、それ以外は 4
            }
            default -> {
                return (op < OPERAND_BYTES.length && OPERAND_BYTES[op] >= 0) ? 1 + OPERAND_BYTES[op] : -1;
            }
        }
    }

    private static int s4(byte[] code, int at) {
        return ((code[at] & 0xFF) << 24) | ((code[at + 1] & 0xFF) << 16)
                | ((code[at + 2] & 0xFF) << 8) | (code[at + 3] & 0xFF);
    }

    /** オペコードごとのオペランドのバイト数。-1 は未定義（JVMS 第 6 章。可変長の 3 つは上で扱う） */
    private static final byte[] OPERAND_BYTES = new byte[0xCA];

    static {
        Arrays.fill(OPERAND_BYTES, (byte) 0);
        OPERAND_BYTES[0x10] = 1;                                   // bipush
        OPERAND_BYTES[0x11] = 2;                                   // sipush
        OPERAND_BYTES[0x12] = 1;                                   // ldc
        OPERAND_BYTES[0x13] = 2;                                   // ldc_w
        OPERAND_BYTES[0x14] = 2;                                   // ldc2_w
        for (int op = 0x15; op <= 0x19; op++) {
            OPERAND_BYTES[op] = 1;                                 // iload .. aload
        }
        for (int op = 0x36; op <= 0x3A; op++) {
            OPERAND_BYTES[op] = 1;                                 // istore .. astore
        }
        OPERAND_BYTES[0x84] = 2;                                   // iinc
        for (int op = 0x99; op <= 0xA8; op++) {
            OPERAND_BYTES[op] = 2;                                 // if* / goto / jsr
        }
        OPERAND_BYTES[0xA9] = 1;                                   // ret
        OPERAND_BYTES[0xAA] = -1;                                  // tableswitch（可変）
        OPERAND_BYTES[0xAB] = -1;                                  // lookupswitch（可変）
        for (int op = 0xB2; op <= 0xB8; op++) {
            OPERAND_BYTES[op] = 2;                                 // getstatic .. invokestatic
        }
        OPERAND_BYTES[0xB9] = 4;                                   // invokeinterface
        OPERAND_BYTES[0xBA] = 4;                                   // invokedynamic
        OPERAND_BYTES[0xBB] = 2;                                   // new
        OPERAND_BYTES[0xBC] = 1;                                   // newarray
        OPERAND_BYTES[0xBD] = 2;                                   // anewarray
        OPERAND_BYTES[0xC0] = 2;                                   // checkcast
        OPERAND_BYTES[0xC1] = 2;                                   // instanceof
        OPERAND_BYTES[0xC4] = -1;                                  // wide（可変）
        OPERAND_BYTES[0xC5] = 3;                                   // multianewarray
        OPERAND_BYTES[0xC6] = 2;                                   // ifnull
        OPERAND_BYTES[0xC7] = 2;                                   // ifnonnull
        OPERAND_BYTES[0xC8] = 4;                                   // goto_w
        OPERAND_BYTES[0xC9] = 4;                                   // jsr_w
    }

    // --- 属性の読み飛ばし ---

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            in.readUnsignedShort();             // name_index
            skipFully(in, in.readInt());
        }
    }

    /** {@link DataInputStream#skipBytes} は要求より少なく飛ばすことがあるので、足りるまで繰り返す */
    private static void skipFully(DataInputStream in, int n) throws IOException {
        while (n > 0) {
            int skipped = in.skipBytes(n);
            if (skipped <= 0) {
                in.readByte();   // 読めなければ EOFException
                skipped = 1;
            }
            n -= skipped;
        }
    }

    // --- 名前の変換 ---

    static String internalToFqn(String internal) {
        return (internal == null) ? null : internal.replace('/', '.');
    }

    /** ディスクリプタ "(Ljava/lang/String;I[Z)V" から引数型のFQN列を取り出す */
    static List<String> parseParams(String desc) {
        List<String> out = new ArrayList<>();
        if (desc == null) {
            return out;
        }
        int i = desc.indexOf('(');
        if (i < 0) {
            return out;
        }
        i++;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int arrayDepth = 0;
            while (i < desc.length() && desc.charAt(i) == '[') {
                arrayDepth++;
                i++;
            }
            if (i >= desc.length()) {
                break;
            }
            String type;
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    break;
                }
                type = desc.substring(i + 1, end).replace('/', '.');
                i = end + 1;
            } else {
                type = primitiveName(c);
                if (type == null) {
                    return out;
                }
                i++;
            }
            out.add(type + "[]".repeat(arrayDepth));
        }
        return out;
    }

    private static String primitiveName(char descriptor) {
        return switch (descriptor) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            default -> null;
        };
    }
}
