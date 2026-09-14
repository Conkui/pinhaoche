package com.pinhaoche.user;

import com.pinhaoche.common.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

/**
 * 头像存储（RFC-011）：base64 → 校验 → 落盘 → 返回可访问的相对路径。
 *
 * 为什么把校验集中在这里：头像是本项目唯一"客户端上传二进制"的入口，也是唯一会写服务端磁盘的入口，
 * 所有安全边界（大小、真实类型、文件名、目录穿越）必须在服务端一次做完，且只在这一处做，
 * 避免调用方各自实现导致漏检。客户端声明的 MIME / 文件名一律不参与任何判断。
 *
 * 降级策略：磁盘不可用时不做假成功——写失败抛可读的 400（见 store 内注释），
 * 旧文件删不掉只记 warn（残留文件只浪费磁盘，不该让用户这次保存失败）。
 */
@Component
public class AvatarStorage {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorage.class);

    /** 解码后原始字节上限 2 MiB（与 openapi UpdateProfileRequest.avatarBase64 的约定一致）。 */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    /**
     * 解码前允许的最大 base64 文本长度。
     *
     * 为什么先卡文本长度再解码：2 MiB 原始字节的 base64 约 2.73MB，如果先解码再判大小，
     * 攻击者提交一个几百 MB 的字符串就能让服务端先分配数倍于它的 byte[]（base64↔二进制膨胀）
     * 再被拒绝，等于白送一个 OOM 面。文本长度判断是 O(1) 的 length()，把拦截点前移后，
     * 恶意请求在分配内存之前就被挡掉；2.8MB 相对 2.73MB 的合法上限留足了 data URL 前缀余量，
     * 不会误伤正常图片。
     */
    public static final int MAX_BASE64_CHARS = (int) (2.8 * 1024 * 1024);

    /** 库内/响应里统一使用的前缀：只存相对路径，host 由小程序拼接，换域名不用洗库。 */
    static final String PUBLIC_PREFIX = "/files/";

    private static final String DATA_URL_PREFIX = "data:";

    /** 判定 SVG 时只扫头部这么多个字节：SVG 的根元素/XML 声明必然在最前面，扫全文纯属浪费。 */
    private static final int SNIFF_HEAD_BYTES = 512;

    private final Path baseDir;

    public AvatarStorage(@Value("${app.upload-dir}") String uploadDir) {
        // toAbsolutePath + normalize：容器内是 /app/data/uploads、Windows 本地是盘符开头的绝对路径，
        // 先统一成"绝对规范路径"再作为目录穿越校验的基准，否则相对路径下 startsWith 判定会失真。
        this.baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            // 启动即建目录：让"磁盘/权限不可用"在启动阶段暴露，而不是等用户点保存时才失败。
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建头像上传目录: " + this.baseDir, e);
        }
    }

    /** 上传目录的绝对规范路径（单测/静态资源映射用）。 */
    public Path baseDirectory() {
        return baseDir;
    }

    /**
     * 校验并保存头像，返回 {@code /files/<uuid>.<ext>} 相对路径。
     * 任何一步不合法都抛 400 VALIDATION_ERROR（由 GlobalExceptionHandler 转成契约里的 ApiError），
     * 保证客户端拿到的永远是可读错误，而不是绝对路径与堆栈。
     */
    public String store(String base64) {
        byte[] bytes = decode(base64);
        String extension = detectExtension(bytes);
        // 文件名完全由服务端生成：UUID 不可枚举（免鉴权静态资源下的最低要求），扩展名只来自魔数白名单。
        // 绝不复用客户端提供的文件名——那是路径穿越（../../x）与覆盖任意文件最经典的入口。
        String name = UUID.randomUUID() + "." + extension;
        Path target = baseDir.resolve(name).normalize();
        if (!target.startsWith(baseDir)) {
            // 理论上不可达（name 由 UUID + 白名单扩展名拼成）；
            // 保留断言是为了防止后人把这里"优化"成使用客户端文件名时静默变成目录穿越漏洞。
            throw ApiException.validation("头像文件名非法");
        }
        try {
            Files.createDirectories(baseDir);
            Files.write(target, bytes);
        } catch (IOException e) {
            // 磁盘满 / 权限不足 / 文件被占用（Windows）：转成可读的 400 而不是让默认 500 页面把
            // 绝对路径与堆栈泄给客户端。代价是服务端故障被标成 400——V1 不允许新增错误码
            // （ErrorCode 是冻结契约），已记入交付说明的 Known Issues。
            log.error("头像落盘失败: dir={}", baseDir, e);
            throw ApiException.validation("头像保存失败，请稍后重试");
        }
        return PUBLIC_PREFIX + name;
    }

    /**
     * 替换头像时清理旧文件。
     * 仅当旧值形如 {@code /files/<纯文件名>}、且解析进上传目录后仍在目录内时才删除：
     * 旧值是历史数据、也可能被人手工改过，直接 resolve+delete 会退化成任意文件删除。
     * 删除失败只记 warn 不抛错——旧文件残留只占磁盘，不该让用户这次保存失败。
     */
    public void deleteIfManaged(String storedPath) {
        if (storedPath == null || !storedPath.startsWith(PUBLIC_PREFIX)) {
            return;
        }
        String name = storedPath.substring(PUBLIC_PREFIX.length());
        // 只接受纯文件名：合法值由 store() 生成，不含任何分隔符；含分隔符的一律不碰。
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            return;
        }
        Path target = baseDir.resolve(name).normalize();
        if (!target.startsWith(baseDir)) {
            log.warn("跳过疑似目录穿越的头像清理: {}", storedPath);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除旧头像失败（已忽略）: {}", target, e);
        }
    }

    /**
     * 解码前拦截 → 剥离 data URL 前缀 → 严格 base64 解码 → 解码后大小拦截。
     * 包级可见：单测直接断言，不依赖 Spring 上下文。
     */
    static byte[] decode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.validation("头像数据为空");
        }
        String text = raw.trim();
        if (text.length() > MAX_BASE64_CHARS) {
            throw ApiException.validation("头像过大，请压缩后重试（上限 2MiB）");
        }
        if (text.regionMatches(true, 0, DATA_URL_PREFIX, 0, DATA_URL_PREFIX.length())) {
            int comma = text.indexOf(',');
            if (comma < 0) {
                throw ApiException.validation("头像数据格式非法");
            }
            // 只剥掉 `data:image/xxx;base64,` 这段前缀本身；前缀里声明的 MIME 一律不采信，
            // 真实类型只由下面的魔数判定（客户端可随意伪造 Content-Type）。
            text = text.substring(comma + 1).trim();
        }
        byte[] bytes;
        try {
            // 严格解码器（getDecoder 而非 getMimeDecoder）：非法字符直接失败，
            // 不静默丢弃脏字符——宽松解码会让"夹带垃圾数据的图片"悄悄通过。
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("头像数据不是合法的 base64");
        }
        if (bytes.length == 0) {
            throw ApiException.validation("头像数据为空");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw ApiException.validation("头像过大，请压缩后重试（上限 2MiB）");
        }
        return bytes;
    }

    /**
     * 按魔数判定真实图片类型并推导扩展名；不合法则抛 400。
     *
     * 为什么白名单 + 显式拒绝 SVG：SVG 本质是可内嵌 &lt;script&gt; 的 XML，
     * 而 /files/** 是免鉴权静态资源（原因见 UploadedFileConfig），浏览器直开即为存储型 XSS；
     * 头像场景没有矢量图需求，直接不允许。GIF/JPEG/PNG/WEBP 是位图，不含脚本能力。
     */
    static String detectExtension(byte[] b) {
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 4
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 4
                && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        // WEBP 是 RIFF 容器：0-3 "RIFF"，4-7 长度，8-11 "WEBP"，两段都匹配才算。
        if (b.length >= 12 && asciiAt(b, 0, "RIFF") && asciiAt(b, 8, "WEBP")) {
            return "webp";
        }
        if (looksLikeSvg(b)) {
            throw ApiException.validation("不支持 SVG 头像，请上传 JPEG/PNG/GIF/WEBP 图片");
        }
        throw ApiException.validation("不支持的图片格式，仅接受 JPEG/PNG/GIF/WEBP");
    }

    private static boolean asciiAt(byte[] b, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (b[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 头部是否像 SVG：含 XML 声明或 &lt;svg 根元素。ISO-8859-1 逐字节映射，不会因非法 UTF-8 丢字节。 */
    private static boolean looksLikeSvg(byte[] b) {
        int head = Math.min(b.length, SNIFF_HEAD_BYTES);
        String text = new String(b, 0, head, StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.ROOT);
        return text.contains("<svg") || text.contains("<?xml") || text.contains("<!doctype svg");
    }
}
