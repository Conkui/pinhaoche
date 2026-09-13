package com.pinhaoche.importer;

/**
 * OCR 接口（规范 §18：截图 → OCR → 平台识别 → 字段解析 → 置信度 → 用户确认）。
 * 应用层只依赖接口；V1 未接入真实 OCR 服务（腾讯云/兼容 OCR），见 Not Completed。
 */
public interface OcrProvider {

    OcrResult recognize(byte[] image);

    record OcrField(String value, double confidence) {
    }

    /**
     * 目标字段（规范 §18）：车牌/出发地/目的地/订单状态/剩余距离/预计到达时间/平台。
     * 置信度低于阈值的字段由调用方要求用户确认，不允许自动提交。
     */
    record OcrResult(String provider, OcrField plateNumber, OcrField originText,
                     OcrField destinationText, OcrField remainingDistance) {
    }
}
