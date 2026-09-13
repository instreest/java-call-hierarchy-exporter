// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

/**
 * サーバーが返す木の1行（{@code R} 行）。
 *
 * <p>画面はこの行だけを見て描く。メソッドID やグラフはサーバー側にあり、こちらへは来ない。
 * だから Eclipse の中に解析のコードを持たずに済む（docs/out-of-process-analysis-design.md）。
 *
 * <p>このパッケージのクラスは <b>Eclipse の API を使わない</b>。プロトコルの検査を
 * Eclipse 無しで回せるようにするためで、{@code test/plugin-client/run.sh} がそれを使う。
 */
public final class ServerRow {

    /** 印（flags 列）。画面のアイコンや色はこれで決まる */
    public static final String FLAG_RECURSIVE = "recursive";
    public static final String FLAG_TRUNCATED = "truncated";
    public static final String FLAG_GUESSED = "guessed";
    public static final String FLAG_MATCH = "match";
    public static final String FLAG_NO_SOURCE = "nosource";

    private final int depth;
    private final String key;
    private final String label;
    private final String file;
    private final int line;
    private final String reason;
    private final String flags;

    ServerRow(int depth, String key, String label, String file, int line, String reason, String flags) {
        this.depth = depth;
        this.key = key;
        this.label = label;
        this.file = file;
        this.line = line;
        this.reason = reason;
        this.flags = flags;
    }

    /** {@code R<TAB>深さ<TAB>キー<TAB>表示名<TAB>ファイル<TAB>行<TAB>理由<TAB>印} を読む。壊れていれば null */
    static ServerRow parse(String[] parts) {
        if (parts.length < 7) {
            return null;
        }
        try {
            return new ServerRow(Integer.parseInt(parts[1]),
                    Wire.unescape(parts[2]), Wire.unescape(parts[3]), Wire.unescape(parts[4]),
                    Integer.parseInt(parts[5]), Wire.unescape(parts[6]),
                    parts.length > 7 ? parts[7] : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 根からの深さ。行は深さ優先の順で来るので、これだけで木に組み直せる */
    public int depth() {
        return depth;
    }

    /** メソッドのキー（型FQN#名前(引数)）。次の問い合わせの起点に使える */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 呼び出している場所のファイル（プロジェクトルートからの相対）。無ければ空 */
    public String file() {
        return file;
    }

    /** 呼び出している行。分からなければ宣言の行 */
    public int line() {
        return line;
    }

    /** 解決の理由（DATAFLOW_FACTORY 等）。ふつうの呼び出しでは空 */
    public String reason() {
        return reason;
    }

    public boolean hasFlag(String flag) {
        if (flags.isEmpty()) {
            return false;
        }
        for (String f : flags.split(",")) {
            if (f.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return depth + " " + key;
    }
}
