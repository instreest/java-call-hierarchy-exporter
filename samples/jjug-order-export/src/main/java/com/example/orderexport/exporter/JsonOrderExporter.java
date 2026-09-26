package com.example.orderexport.exporter;

import java.util.List;

import com.example.orderexport.domain.Order;

import tools.jackson.databind.json.JsonMapper;

/** 画面 API 向けの JSON。受注のレコードを Jackson でそのまま書き出す */
public class JsonOrderExporter implements OrderExporter {

    @Override
    public String export(List<Order> orders) {
        return JsonMapper.shared().writeValueAsString(orders);
    }
}
