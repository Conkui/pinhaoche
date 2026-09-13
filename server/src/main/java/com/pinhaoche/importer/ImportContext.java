package com.pinhaoche.importer;

/**
 * 第三方平台导入上下文（规范 §16）。
 * V1 只携带平台标识；禁止携带任何抓包/Hook/破解签名产物（规范 §17 红线）。
 */
public record ImportContext(String provider) {
}
