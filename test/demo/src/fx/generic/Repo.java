package fx.generic;

/**
 * 型引数を具体化した実装を CHA の候補に入れられるかの確認用（JLS 8.4.2 のサブシグネチャ）。
 *
 * このメソッドのキーは消去後の {@code save(java.lang.Object)} になるので、
 * {@code OrderRepo#save(fx.generic.Order)} とは文字列として一致しない。
 * 上書き関係（O行）を見ていないと、候補が {@code RawRepo} 1 件だけになって
 * 実際には動かない実装に誤確定する。
 */
public interface Repo<T> {
    void save(T item);
}
