package com.pinhaoche;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 拼好车 V1 后端入口。
 * 模块化单体：所有业务模块同进程部署，模块间只允许通过 application service 与 DTO 交互，
 * 禁止跨模块直接访问对方 repository/表，保证未来可以独立拆分。
 */
@EnableScheduling
@SpringBootApplication
public class PinHaoCheApplication {

    public static void main(String[] args) {
        SpringApplication.run(PinHaoCheApplication.class, args);
    }
}
