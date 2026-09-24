package vals;

// V5 / V6: ファクトリのキーが | や ; を含む（契約表は contracts.txt）。
// 移す前: キーを | ; の手前で切って "USER" / "A" と読み、短いほうの行に当てて UserDaoImpl にする（誤り）。
// 移した後: "USER|X" / "A;B" の行に当てて OrderDaoImpl にする
// V5s: 契約表に無いキー "Z|W"。候補が 2 つのまま（CHA）なので、契約表のひな形（contracts-suggested.txt）に出る。
// 移す前: キーを切って get("Z") の行を勧める（そのまま貼っても効かない）。移した後: get("Z|W") の行を勧める
public class Keys {
    public static void v5s() {
        DaoFactory.get("Z|W").find();
    }

    public static void v5() {
        DaoFactory.get("USER|X").find();
    }

    public static void v6() {
        DaoFactory.get("A;B").find();
    }
}
