package fx.inherit;

/**
 * Greeter の実装だが greet() 自体は親クラス BaseGreeter から継承している。
 * Greeter#greet の CHA 候補には BaseGreeter.greet が入らなければならない
 * （サブタイプの直接の宣言だけを見ると候補 0 件＝「実装なし」に落ちていた）
 */
public class PoliteGreeter extends BaseGreeter implements Greeter {
}
