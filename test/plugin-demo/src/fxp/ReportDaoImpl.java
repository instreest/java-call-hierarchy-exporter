package fxp;

/** find() を宣言せず AbstractDao から継承する。拡張はこの型を返す（Issue #131） */
public class ReportDaoImpl extends AbstractDao implements ReportDao {
    void onlyReport() {
    }
}
