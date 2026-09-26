package com.example.orderexport.exporter;

import java.util.List;

import com.example.orderexport.domain.Order;

/**
 * 受注の一覧を、外部に渡す形式の文字列にする。
 * どの形式で書き出すかは呼び出し元が決め、実装を作って渡す（画面 API は JSON、取引先連携は XML）。
 */
public interface OrderExporter {

    String export(List<Order> orders);
}
