package com.example.orderexport.exporter;

import java.util.List;

import com.example.orderexport.domain.Order;

/**
 * 受注の一覧を、外部に渡す形式の文字列にする。
 * 実装は Spring の Bean で、どれを使うかは呼び出し元が決める（画面 API は JSON、取引先連携は XML、
 * 取引先別の連携ファイルは取引先マスタの設定で OrderExporterFactory が選ぶ）。
 */
public interface OrderExporter {

    /** 取引先マスタの連携形式（partners.export_format）に書く名前 */
    String format();

    String export(List<Order> orders);
}
