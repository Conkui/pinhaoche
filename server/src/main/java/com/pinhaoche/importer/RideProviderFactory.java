package com.pinhaoche.importer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据来源选择 Adapter（规范 §16）。没有 supports=true 的实现时返回空 → 调用方降级手动填写。
 */
@Component
public class RideProviderFactory {

    private final List<RideProviderAdapter> adapters;

    public RideProviderFactory(List<RideProviderAdapter> adapters) {
        this.adapters = adapters;
    }

    public java.util.Optional<RideProviderAdapter> select(ImportContext context) {
        return adapters.stream().filter(a -> a.supports(context)).findFirst();
    }
}
