package com.example.orderexport.exporter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.orderexport.domain.Order;

import tools.jackson.databind.json.JsonMapper;

/** 画面 API 向けの JSON。Spring Boot が用意する JsonMapper（Jackson）でそのまま書き出す */
@Component
public class JsonOrderExporter implements OrderExporter {

    private final JsonMapper jsonMapper;

    public JsonOrderExporter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String export(List<Order> orders) {
        return jsonMapper.writeValueAsString(orders);
    }
}
