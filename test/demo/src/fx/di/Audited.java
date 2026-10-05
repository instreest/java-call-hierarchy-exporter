// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 利用者が独自に定めた Bean 登録の印（Spring のステレオタイプ注釈を合成したもの、の見立て）。
 * 既定では Bean の印とみなされず、設定の {@code spring.di.bean.annotations} に単純名 {@code Audited} を
 * 足したときだけ同じ扱いになる（回帰テストの diannot ケース）
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
}
