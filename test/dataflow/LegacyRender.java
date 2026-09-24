// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.graph.GuardTable;
import jche.graph.ValueStore;

/**
 * 検査用: 値の表（{@link ValueStore}）のノードを、出所の文字列に組み直す。
 *
 * <p>{@code jche.graph.OriginRenderer} がキャッシュの N 行から組み直すのと<b>同じ手順</b>を、表の上でなぞる
 * （文字数の予算 {@link #BUDGET} と深さの安全弁 {@link #MAX_DEPTH} も同じ）。表が正しく取り込めていれば、
 * どの呼び出し箇所でも、ここで組み直した文字列と今の読み手が受け取っている文字列は 1 文字も違わない
 * （ValueStoreCheck の (1)）。予算か安全弁に当たった回数を {@link #cutOffs} に数える（表は打ち切らないので、
 * 当たると文字列の側だけが情報を落としている）。
 */
final class LegacyRender {

    private static final int BUDGET = 64 * 1024;
    private static final int MAX_DEPTH = 512;

    private final ValueStore values;
    private final Map<Integer, String> memo = new HashMap<>();
    /** 予算か安全弁に当たった回数 */
    int cutOffs;

    LegacyRender(ValueStore values) {
        this.values = values;
    }

    /** ノード 1 つを出所の文字列にする。{@link ValueStore#NONE} なら null */
    String render(int ref) {
        return render(ref, 0);
    }

    /** 実引数の並びのノード（{@link ValueStore#ARG_LIST}）を {@code 位置=出所;…} にする。{@link ValueStore#NONE} なら null */
    String renderArgs(int holder) {
        if (holder == ValueStore.NONE) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = values.argBegin(holder); k < values.argEnd(holder); k++) {
            appendEntry(sb, k, 0);
        }
        return sb.toString();
    }

    private void appendEntry(StringBuilder sb, int k, int depth) {
        String origin = render(values.argRef(k), depth);
        if (origin == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(values.argPos(k)).append('=').append(LegacyOrigin.nest(origin));
    }

    private boolean hasEntries(int ref) {
        return values.argBegin(ref) < values.argEnd(ref) || values.argCount(ref) >= 0
                || values.receiver(ref) != ValueStore.NONE || values.staticReceiver(ref) != null;
    }

    private String render(int ref, int depth) {
        if (ref == ValueStore.NONE) {
            return null;
        }
        String cached = memo.get(ref);
        if (cached != null) {
            return cached;
        }
        char kind = values.kind(ref);
        String head = LegacyOrigin.of(kind, values.value(ref));
        if (!hasEntries(ref)) {
            memo.put(ref, head);
            return head;
        }
        if (depth >= MAX_DEPTH) {
            cutOffs++;
            return head;
        }
        String result = withArgs(ref, kind, head, depth);
        if (result.length() != head.length()) {
            memo.put(ref, result);
        }
        return result;
    }

    private String withArgs(int ref, char kind, String head, int depth) {
        String args = argList(ref, depth + 1);
        if (kind == 'T') {
            return LegacyOrigin.of(kind, values.value(ref), args);
        }
        if (values.argCount(ref) >= 0) {
            String count = LegacyOrigin.ARG_COUNT + "=" + values.argCount(ref);
            args = args.isEmpty() ? count : args + ";" + count;
        }
        if (values.receiver(ref) != ValueStore.NONE) {
            String recv = render(values.receiver(ref), depth + 1);
            if (recv != null) {
                String entry = LegacyOrigin.RECEIVER + "=" + LegacyOrigin.nest(recv);
                args = args.isEmpty() ? entry : args + ";" + entry;
            }
        }
        String s = values.staticReceiver(ref);
        if (s != null && !s.isEmpty()) {
            args = args.isEmpty() ? LegacyOrigin.STATIC_RECV + "=" + s
                    : args + ";" + LegacyOrigin.STATIC_RECV + "=" + s;
        }
        if (head.length() + args.length() > BUDGET) {
            cutOffs++;
            return head;
        }
        return LegacyOrigin.of(kind, values.value(ref), args);
    }

    private String argList(int ref, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int k = values.argBegin(ref), end = values.argEnd(ref); k < end; k++) {
            if (sb.length() > BUDGET) {
                cutOffs++;
                break;
            }
            appendEntry(sb, k, depth);
        }
        return sb.toString();
    }

    /**
     * 条件の表のガード 1 つを、今の読み手が受け取る条件の文字列（{@code jche.cache.Guard} の形）にする。
     * subject はノードの頭（{@code 種別:値}）を、今の組み方と同じく最初の {@code |} で切ってから置く。
     * 知らない種別のアトムは組み直せない（G 行の op の綴りを持たない）ので {@code ?} にする。
     * {@link GuardTable#NONE} なら null
     */
    static String renderGuard(ValueStore values, GuardTable guards, int g) {
        if (g == GuardTable.NONE) {
            return null;
        }
        List<String> atoms = new ArrayList<>();
        for (int a = guards.atomBegin(g); a < guards.atomEnd(g); a++) {
            int subject = guards.subject(a);
            String head = (subject == ValueStore.NONE) ? "U"
                    : LegacyOrigin.head(LegacyOrigin.of(values.kind(subject), values.value(subject)));
            List<String> vs = new ArrayList<>();
            for (int k = guards.valueBegin(a); k < guards.valueEnd(a); k++) {
                vs.add(guards.value(k));
            }
            atoms.add(LegacyOrigin.atom(opText(guards.op(a)), head, LegacyOrigin.values(vs), guards.text(a)));
        }
        return LegacyOrigin.join(atoms);
    }

    private static String opText(byte op) {
        return switch (op) {
            case GuardTable.EQ -> "EQ";
            case GuardTable.NE -> "NE";
            case GuardTable.IN -> "IN";
            case GuardTable.NOT_IN -> "NI";
            default -> "?";
        };
    }
}
