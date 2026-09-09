package demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;

/**
 * 自前の拡張の例。DI 設定ファイル（このプロジェクト独自の形式）を読んで、
 * インターフェースの宣言型から注入される具象クラスを返す。
 *
 * 同梱の TypeMappingProvider は「宣言型 = 具象型」の対応表しか読まないので、
 * 設定ファイルの形式が独自のときはこのように自分で書く。plugin.folders に
 * この .java を置くだけでよく、ビルドは要らない。
 */
public class DiXmlProvider implements TypeCandidateProvider {

    private static final Pattern BEAN =
            Pattern.compile("<bean\\s+type=\"([^\"]+)\"\\s+class=\"([^\"]+)\"");

    private final Map<String, String> beans = new HashMap<>();

    @Override
    public void init(Properties config, Path configDir) {
        Path file = configDir.resolve(config.getProperty("demo.di.file", "di.xml").trim());
        try {
            Matcher m = BEAN.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                beans.put(m.group(1), m.group(2));
            }
        } catch (IOException e) {
            throw new IllegalStateException("DI 設定ファイルを読めません: " + file, e);
        }
    }

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        String impl = beans.get(declaredType);
        return (impl == null) ? null : new String[] {impl};
    }

    @Override
    public String label() {
        return "DI_XML";
    }
}
