// JLS SE 26 §7.3 コンパクトなコンパイル単位 / §8.1.8 暗黙に宣言されるクラス / §12.1.4 main メソッドの呼び出し
//
// パッケージ宣言も型宣言も無いファイルは「コンパクトなコンパイル単位」で、トップレベルの
// メソッドは、暗黙に宣言された final なトップレベルのクラス（名前はファイル名・無名パッケージ）のメンバになる（§7.3 / §8.1.8）。
// 起動の入口は public static void main(String[]) に限られず、引数なしのもの・インスタンスメソッドも
// 含む（§12.1.4）。ここではその両方にあたる「引数なしのインスタンスメソッド main()」を置く。
//
// 検査（test/jls/expect.tsv の 7.3 / 8.1.8 / 12.1.4 の行）:
//   - 暗黙に宣言されたクラス CompactMain が型として読めること
//   - main() -> greet() -> jls.s07_03.CompactTarget.hello() の辺があること
//   - main() が起動の入口（role が FRAMEWORK_ENTRY）と判定されること

void main() {
    greet();
}

void greet() {
    jls.s07_03.CompactTarget.hello();
}
