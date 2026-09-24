package vals;

// V5 / V6: ファクトリのキーが | や ; を含む（契約表は contracts.txt）。
// 期待: "USER|X" / "A;B" の行に当てて OrderDaoImpl にする。
// 以前の誤り: キーを | ; の手前で切って "USER" / "A" と読み、短いほうの行に当てて UserDaoImpl にしていた。
// V5s: 契約表に無いキー "Z|W"。候補が 2 つのまま（CHA）なので、契約表のひな形（contracts-suggested.txt）に出る。
// 期待: get("Z|W") の行を勧める。以前の誤り: キーを切って get("Z") の行を勧めていた（そのまま貼っても効かない）
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
