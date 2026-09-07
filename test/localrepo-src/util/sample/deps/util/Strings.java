package sample.deps.util;

/** sample.deps:util:1.0 の中の型。Gradle の版カタログ（libs.sample.util）から参照される */
public final class Strings {
    private Strings() {
    }

    public static String upper(String s) {
        return s.toUpperCase();
    }
}
