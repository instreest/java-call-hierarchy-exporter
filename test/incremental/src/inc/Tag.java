// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/**
 * 既定値を持つ注釈。{@code @Tag} とだけ書いても既定値が使う側の行に焼き込まれるので、
 * 既定値を書き換えたら使う側も解析し直す必要がある
 */
public @interface Tag {

    String value() default "alpha";
}
