package com.pinhaoche.common.util;

/**
 * 文本入库前净化（RFC-010）：剥离控制字符并 trim。
 *
 * 为什么要净化：0x00-0x1F 中的控制字符没有任何业务语义，却会破坏下游链路——
 * MySQL 对部分控制字符剥离或报错、日志伪造（注入换行冒充日志行）、前端渲染错乱、
 * 经 WebSocket 原样推给对方后污染聊天记录。在写库前统一净化一次（聊天/反馈共用本入口），
 * 保证库内永远只存"干净"文本，属于防御纵深的最外层。
 *
 * 保留 \n（0x0A）与 \t（0x09）：多行反馈/消息是正常用户输入，不能误伤。
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /**
     * 剥离控制字符并 trim；null 安全（null 进 null 出，调用方无需先判空）。
     * 剥离范围：0x00-0x08、0x0B、0x0C、0x0E-0x1F（即除 \t \n 外全部 < 0x20 的字符）。
     * 时间复杂度 O(n)，内容最大 2000 字（聊天）/500 字（反馈），无性能压力。
     */
    public static String clean(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // c < 0x20 覆盖 0x00-0x1F；排除 \t(0x09) 与 \n(0x0A) 即得规范要求的剥离范围。
            // 0x7F(DEL) 与高位字符不在范围内，按规范原样保留。
            boolean control = c < 0x20 && c != '\t' && c != '\n';
            if (!control) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
