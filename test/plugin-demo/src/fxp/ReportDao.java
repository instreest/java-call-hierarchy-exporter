package fxp;

/**
 * Class リテラルをキーにするファクトリ用のインターフェース。
 *
 * {@code DaoFactory.get(ReportDao.class)} の戻り値は {@code Dao} 型なので、
 * ソースだけ見ても具象クラスは決まらない（CHA で実装の数だけ広がる）。
 * 実引数の {@code ReportDao.class} をキーにして 1 件に絞れるかを見る
 */
public interface ReportDao extends Dao {
}
