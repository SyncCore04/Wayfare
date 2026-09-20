package com.wayfare;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wayfare 启动类
 */
@SpringBootApplication
@MapperScan("com.wayfare.mapper")
public class WayfareApplication {

    private static final Logger log = LoggerFactory.getLogger(WayfareApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(WayfareApplication.class, args);
        // 用 slf4j 而非 System.out：与全项目日志配置（级别、格式、落盘）保持一致
        log.info("""
                ====================================================
                  Wayfare 后端启动成功
                  接口文档: http://localhost:8080/api/swagger-ui/index.html
                  健康检查: http://localhost:8080/api/health
                ====================================================""");
    }
}
