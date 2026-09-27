package jls.s14_14_02;

import java.util.Iterator;
import java.util.List;

/**
 * JLS SE 26 §14.14.2 の {@code Expression.iterator()} は、式の型のメンバ（§8.4.8・§15.12.1）の呼び出しである。
 *
 * 式の型 ChainColl は、親の親 ChainIterBase のクラスのメソッド {@code iterator()} と、直接のインターフェース
 * ChainIterApi の既定のメソッド {@code iterator()} を持つ。クラスのメソッドが選ばれる（§8.4.8）ので、呼び出し先は
 * ChainIterBase.iterator()（javac の所有型 ChainColl から引く宣言も同じ）。
 * （型変数の式で親クラスの private な iterator() を拾わないことは test/pruning の MemberIter で見る）
 */
public class MemberIterator {
    static int viaClassChain(ChainColl c) {
        int n = 0;
        for (String s : c) {
            n += s.length();
        }
        return n;
    }
}

class ChainIterBase implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
        return List.of("b").iterator();
    }
}

class ChainIterMid extends ChainIterBase {
}

interface ChainIterApi extends Iterable<String> {
    @Override
    default Iterator<String> iterator() {
        return List.of("c").iterator();
    }
}

class ChainColl extends ChainIterMid implements ChainIterApi {
}
