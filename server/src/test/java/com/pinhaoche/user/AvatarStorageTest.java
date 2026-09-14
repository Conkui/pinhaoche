package com.pinhaoche.user;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 头像存储安全边界测试（RFC-011）：不启动 Spring 上下文，直接构造存储实例指向 @TempDir，
 * 保证测试不会向仓库工作目录写任何文件。
 */
class AvatarStorageTest {

    @TempDir
    Path tempDir;

    private AvatarStorage storage() {
        return new AvatarStorage(tempDir.toString());
    }

    /** 最小 PNG：8 字节标准签名 + 一段填充，够魔数识别即可（本测试只验证识别与落盘，不校验图片完整性）。 */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R', 0x01, 0x02, 0x03,
    };

    /** 最小 JPEG：FF D8 FF E0 (SOI + APP0) 开头。 */
    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            'J', 'F', 'I', 'F', 0x00, 0x10, 0x01, 0x02,
    };

    private static final byte[] GIF = {
            'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00, 0x00,
    };

    private static final byte[] WEBP = {
            'R', 'I', 'F', 'F', 0x1A, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ',
    };

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    @Test
    void storesPngWithServerGeneratedName() throws IOException {
        String path = storage().store(base64(PNG));

        // 相对路径 + 扩展名来自魔数；文件名是服务端生成的 UUID（不可枚举）
        assertThat(path).matches("/files/[0-9a-f-]{36}\\.png");
        Path written = tempDir.resolve(path.substring("/files/".length()));
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readAllBytes(written)).isEqualTo(PNG);
    }

    @Test
    void extensionComesFromMagicBytesNotClientClaim() throws IOException {
        // 客户端在 data URL 前缀里声明 png，真实字节是 JPEG：必须以魔数为准
        String path = storage().store("data:image/png;base64," + base64(JPEG));

        assertThat(path).endsWith(".jpg");
        assertThat(fileCount()).isEqualTo(1);
    }

    @Test
    void detectsAllWhitelistedFormats() {
        AvatarStorage storage = storage();
        assertThat(storage.store(base64(JPEG))).endsWith(".jpg");
        assertThat(storage.store(base64(PNG))).endsWith(".png");
        assertThat(storage.store(base64(GIF))).endsWith(".gif");
        assertThat(storage.store(base64(WEBP))).endsWith(".webp");
    }

    @Test
    void stripsDataUrlPrefixBeforeDecoding() throws IOException {
        String path = storage().store("data:image/png;base64," + base64(PNG));

        assertThat(path).endsWith(".png");
        assertThat(Files.readAllBytes(tempDir.resolve(path.substring("/files/".length()))))
                .isEqualTo(PNG);
    }

    @Test
    void rejectsSvgBecauseItCanCarryScripts() throws IOException {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        String xmlSvg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>";

        assertThatThrownBy(() -> storage().store(base64(svg.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> storage().store(
                "data:image/svg+xml;base64," + base64(xmlSvg.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class);
        // 前导空白 + BOM 也不能绕过头部分析
        assertThatThrownBy(() -> storage().store(
                base64(("\uFEFF  \n" + svg).getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class);
        assertThat(fileCount()).isZero();
    }

    @Test
    void rejectsNonImageBytesAndGarbageBase64() throws IOException {
        assertThatThrownBy(() -> storage().store(base64("hello, i am not an image".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> storage().store("!!!not-base64!!!"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage().store(base64(new byte[]{(byte) 0xFF, (byte) 0xD8})))
                .isInstanceOf(ApiException.class); // 魔数不完整
        assertThat(fileCount()).isZero();
    }

    @Test
    void rejectsDecodedPayloadOverTwoMebibytes() throws IOException {
        byte[] tooBig = new byte[AvatarStorage.MAX_IMAGE_BYTES + 1];
        System.arraycopy(PNG, 0, tooBig, 0, PNG.length); // 前缀合法，仅体积超标

        assertThatThrownBy(() -> storage().store(base64(tooBig)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(fileCount()).isZero();
    }

    @Test
    void rejectsOversizedBase64TextBeforeDecoding() throws IOException {
        // 超长 base64 文本：必须在解码前被拒（否则先分配数倍内存再判大小，等于送 OOM 面）。
        // 这里只能从行为上断言"拒绝且不落盘"——解码前/后的拒绝共用同一条可读文案，
        // 故意不让客户端从错误信息反推服务端的内部分支。
        String tooLong = "A".repeat(AvatarStorage.MAX_BASE64_CHARS + 4);

        assertThatThrownBy(() -> storage().store(tooLong))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(fileCount()).isZero();
    }

    @Test
    void emptyInputIsRejected() {
        AvatarStorage storage = storage();
        assertThatThrownBy(() -> storage.store(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("data:image/png;base64,")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("data:image/png;base64")).isInstanceOf(ApiException.class);
    }

    @Test
    void deletesOnlyFilesInsideUploadDir() throws IOException {
        AvatarStorage storage = storage();
        String stored = storage.store(base64(PNG));
        Path written = tempDir.resolve(stored.substring("/files/".length()));

        // 目录穿越的旧值：/files/../<name> 正好指向上传目录的同级文件，
        // 若 deleteIfManaged 少了 "解析后必须仍在目录内" 的校验，这个文件就会被删掉。
        Path outside = Files.createTempFile(tempDir.getParent(), "phc-trespass-", ".txt");
        Files.writeString(outside, "keep me");
        try {
            storage.deleteIfManaged("/files/../" + outside.getFileName());
            storage.deleteIfManaged("/files/" + tempDir.getFileName() + "/../" + outside.getFileName());
            storage.deleteIfManaged("/files/");
            storage.deleteIfManaged("/files");
            storage.deleteIfManaged("http://evil/x.png");
            storage.deleteIfManaged(null);
            assertThat(Files.exists(outside)).isTrue();
            assertThat(Files.exists(written)).isTrue(); // 上面几次调用都不该误删自己的文件
        } finally {
            Files.deleteIfExists(outside);
        }

        // 正常旧值（服务端自己生成的相对路径）必须被清掉，避免替换头像后磁盘无限累积
        storage.deleteIfManaged(stored);
        assertThat(Files.exists(written)).isFalse();
    }
}
