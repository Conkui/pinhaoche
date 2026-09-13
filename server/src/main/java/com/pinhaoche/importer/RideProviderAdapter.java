package com.pinhaoche.importer;

/**
 * 第三方平台 Adapter 接口（规范 §16，契约原文）。
 * Factory 只从已注册 Adapter 中选择 supports=true 的实现；
 * 没有合规 Adapter 时必须降级为"需手动填写/用户确认"，不能虚构平台接口（规范 §40）。
 */
public interface RideProviderAdapter {

    boolean supports(ImportContext context);

    RideImportResult importRide(ImportContext context);
}
