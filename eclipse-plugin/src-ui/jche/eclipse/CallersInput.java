// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

/**
 * ツリーへの入力ひとそろい。「どのスナップショットを、どのフィルタで、どのメソッドを根にして見るか」。
 *
 * <p>この3つはいつも一緒に差し替わる。別々に持つと、解析が終わった瞬間に
 * 「新しいスナップショット＋古い根のID」という組み合わせができてしまう（IDは解析ごとに変わる）。
 *
 * @param model モデル（スナップショットとフィルタ）
 * @param root  根のノード。根のメソッドが今の結果に無ければ null
 */
record CallersInput(CallersModel model, CallNode root) {
}
