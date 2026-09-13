// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 要求1件ぶんの応答（{@code OK} か {@code NG} の行と、それまでに来た {@code R} 行）。 */
public final class ServerResponse {

    private final boolean ok;
    private final Map<String, String> fields;
    private final List<ServerRow> rows;
    private final String raw;

    ServerResponse(boolean ok, Map<String, String> fields, List<ServerRow> rows, String raw) {
        this.ok = ok;
        this.fields = fields;
        this.rows = rows;
        this.raw = raw;
    }

    static ServerResponse of(String line, List<ServerRow> rows) {
        String[] parts = line.split(Wire.SEP, -1);
        boolean ok = "OK".equals(parts[0]);
        Map<String, String> fields = new LinkedHashMap<String, String>();
        List<String> words = new ArrayList<String>();
        for (int i = 1; i < parts.length; i++) {
            String word = parts[i];
            int eq = word.indexOf('=');
            if (eq > 0) {
                fields.put(word.substring(0, eq), Wire.unescape(word.substring(eq + 1)));
            } else if (!word.isEmpty()) {
                words.add(Wire.unescape(word));
            }
        }
        if (!words.isEmpty()) {
            // NG の理由のように key=value でない語は、まとめて reason として持つ
            fields.put("reason", String.join(" ", words));
        }
        return new ServerResponse(ok, fields, rows, line);
    }

    public boolean isOk() {
        return ok;
    }

    /** 失敗の理由（{@code not-analyzed} など）。成功なら空 */
    public String reason() {
        String reason = fields.get("reason");
        return (reason == null) ? "" : reason;
    }

    public String field(String name) {
        String value = fields.get(name);
        return (value == null) ? "" : value;
    }

    public long longField(String name, long fallback) {
        try {
            return Long.parseLong(field(name).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code TREE} の結果。深さ優先の順に並んでいる */
    public List<ServerRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    /** 応答の行そのもの。ログや不具合の調査用 */
    public String raw() {
        return raw;
    }
}
