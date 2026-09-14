package com.pinhaoche.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 头像静态资源映射（RFC-011）：GET /files/** → app.upload-dir 目录。
 *
 * 为什么这个映射必须免鉴权：小程序 &lt;image src&gt; 无法携带 Authorization 头（不是 fetch 封装，
 * 请求由微信客户端发出），若挂到 /api/v1/** 的 token 拦截下，头像将永远 404。
 * 代价是"知道文件名即可读"——用不可枚举的 UUID 文件名缓解，且头像本身是用户主动公开的信息，
 * RFC-011 已记录该权衡（V2 若接对象存储可用带签名的临时 URL 收敛）。
 *
 * 为什么用 URI 字符串而不是裸目录字符串：location 会被 Spring 解析为 FileUrlResource，
 * "file:E:\..." 的反斜杠在 Windows 本地会解析失败，而 "file:///E:/..." 与容器内
 * "file:///app/data/uploads/" 两种形态都能正确解析——一套配置同时覆盖本机与容器。
 */
@Configuration
public class UploadedFileConfig implements WebMvcConfigurer {

    private static final String FILES_PATTERN = "/files/**";

    private final String location;

    public UploadedFileConfig(@Value("${app.upload-dir}") String uploadDir) {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            // 目录不存在时启动即建：否则 Spring 启动期就校验 location 是否可读，
            // 目录缺失会让静态资源解析静默 404，排查成本远高于启动时报错。
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建头像上传目录: " + dir, e);
        }
        String uri = dir.toUri().toString();
        // 目录已存在时 toUri() 会自带结尾 "/"；这里再兜一层，避免结尾缺 "/" 时
        // PathResourceResolver 把 "uploads" 当成文件名、把父目录当成根，导致所有头像 404。
        this.location = uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 只注册 /files/**，不动 Boot 默认的 /**（自定义 /** 会覆盖掉 classpath 静态资源与 webjars）。
        // 同一张资源 Handler 映射内按 pattern 具体度选中，/files/** 比 /** 更具体，
        // 且项目内不存在 /** 的 @RequestMapping，不会有人抢这条路径。
        registry.addResourceHandler(FILES_PATTERN).addResourceLocations(location);
    }
}
