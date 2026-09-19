// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package javax.servlet.http;

/**
 * 解析の確認用に置いた Servlet API のスタブ（本物には依存しない）。
 * 実際のプロジェクトでは jar の中にあるが、契約の判定は H 行の親型の名前で行うので、
 * ソースにあっても jar にあっても同じ結果になる。
 */
public abstract class HttpServlet {

    protected void doGet(HttpServletRequest req, HttpServletResponse res) {
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse res) {
    }
}
