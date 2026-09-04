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
package jche.config;

import java.util.ArrayList;
import java.util.List;

/**
 * エントリポイント指定・除外指定で使うパターン。
 *
 * <pre>
 *   jp.co.xxx.action.*                  … そのパッケージ直下のクラス全部
 *   jp.co.xxx.action.**                 … そのパッケージ配下（サブパッケージ含む）全部
 *   jp.co.xxx.action.UserAction         … クラス指定（内部クラスは Outer.Inner）
 *   jp.co.xxx.action.UserAction#execute … メソッド指定
 * </pre>
 */
public final class PackagePattern {

    private enum Kind { PACKAGE_DIRECT, PACKAGE_RECURSIVE, TYPE, METHOD }

    private final Kind kind;
    private final String value;
    private final String methodName;

    private PackagePattern(Kind kind, String value, String methodName) {
        this.kind = kind;
        this.value = value;
        this.methodName = methodName;
    }

    public static PackagePattern parse(String raw) {
        String s = raw.trim();
        if (s.endsWith(".**")) {
            return new PackagePattern(Kind.PACKAGE_RECURSIVE, s.substring(0, s.length() - 3), null);
        }
        if (s.endsWith(".*")) {
            return new PackagePattern(Kind.PACKAGE_DIRECT, s.substring(0, s.length() - 2), null);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            return new PackagePattern(Kind.METHOD, s.substring(0, hash), s.substring(hash + 1));
        }
        return new PackagePattern(Kind.TYPE, s, null);
    }

    public static List<PackagePattern> parseAll(List<String> raws) {
        List<PackagePattern> out = new ArrayList<>();
        for (String s : raws) {
            if (!s.trim().isEmpty()) {
                out.add(parse(s));
            }
        }
        return out;
    }

    public boolean matches(String pkg, String typeFqn, String method) {
        return switch (kind) {
            // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
            case PACKAGE_DIRECT -> pkg.equals(value);
            case PACKAGE_RECURSIVE -> pkg.equals(value) || pkg.startsWith(value + ".");
            case TYPE -> typeFqn.equals(value);
            case METHOD -> typeFqn.equals(value) && method.equals(methodName);
        };
    }

    public static boolean matchesAny(List<PackagePattern> patterns,
                                     String pkg, String typeFqn, String method) {
        for (PackagePattern p : patterns) {
            if (p.matches(pkg, typeFqn, method)) {
                return true;
            }
        }
        return false;
    }
}
