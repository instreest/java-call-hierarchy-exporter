// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** 複数行の文字列を値に取る注釈（Spring Data の @Query のような形） */
public @interface Sql {

    String value();
}
