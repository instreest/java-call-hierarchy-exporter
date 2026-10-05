package sample.gradle.kts;

import sample.deps.util.Strings;

/** kts プロジェクト（build.gradle.kts）。app からは project(':kts') で参照される（app のテストではソースは解析対象にしない） */
public class Kts {
    public static String shout(String text) {
        return Strings.upper(text);
    }
}
