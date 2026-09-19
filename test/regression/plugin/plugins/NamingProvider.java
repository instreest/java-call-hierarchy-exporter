package demo;

import java.util.List;

import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;

/**
 * 自前の拡張の例（算出規則）。ファクトリに渡されたキーからクラス名を組み立てる。
 *
 * キーが列挙できるなら契約表の 1 行で済むので、拡張を書く価値があるのは
 * 「キーが多すぎて並べたくない」「増え続ける」場合だけ。
 *
 * この例は resolver.hint.collectors（フェーズAの拡張）を設定していない。ファクトリのキーは
 * データフローの値グラフに載っていて、ツールが Hint にして渡すため（jche.graph.FactoryCalls）。
 */
public class NamingProvider implements TypeCandidateProvider {

    /** このファクトリから来た値だけに規則を当てる */
    private static final String FACTORY = "fxp.DaoFactory#get";

    @Override
    public String[] candidates(String declaredType, String signature, List<Hint> hints) {
        if (!hints.contains(new Hint(Hint.KIND_FACTORY, FACTORY))) {
            return null;
        }
        for (Hint hint : hints) {
            if (Hint.KIND_FACTORY_KEY.equals(hint.kind())) {
                return new String[] {"fxp." + camel(hint.value()) + "Impl"};
            }
        }
        return null;   // 証拠が無ければ何も言わない（CHA に任せる）
    }

    @Override
    public String label() {
        return "NAMING";
    }

    /** USER_DAO -> UserDao */
    private static String camel(String key) {
        StringBuilder sb = new StringBuilder();
        for (String word : key.split("_")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
