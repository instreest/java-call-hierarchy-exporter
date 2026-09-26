package com.example.orderexport.exporter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 連携形式の名前（取引先マスタの export_format）から OrderExporter を選ぶ。
 * Spring が OrderExporter の Bean を全部 List で渡すので、format() をキーにした表にしておく。
 * 形式を足すときは OrderExporter の実装を @Component で 1 つ足すだけでよく、ここは変えない。
 */
@Component
public class OrderExporterFactory {

    private final Map<String, OrderExporter> exportersByFormat;

    public OrderExporterFactory(List<OrderExporter> exporters) {
        this.exportersByFormat = exporters.stream()
                .collect(Collectors.toUnmodifiableMap(OrderExporter::format, Function.identity()));
    }

    public OrderExporter get(String format) {
        OrderExporter exporter = exportersByFormat.get(format);
        if (exporter == null) {
            throw new IllegalArgumentException("unsupported export format: " + format);
        }
        return exporter;
    }
}
