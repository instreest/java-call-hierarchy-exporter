// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package dup;

/** dup-b.jar の側。run(int) を持つので、run(1) はこちらに解決される */
public class Shared {

    public void run(int n) {
    }

    public void run(long n) {
    }
}
