// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** Bean が2つある。どちらが注入されるかは @Qualifier の指定で決まる */
public interface Notice {

    void send();
}
