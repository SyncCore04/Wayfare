package com.wayfare.controller;

import com.wayfare.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>无需认证，用于服务可用性检测与部署探活。
 * 注意 server.servlet.context-path 是 {@code /api}，所以真实地址是
 * {@code GET http://localhost:8080/api/health}，不是 {@code /health}。
 */
@RestController
public class HealthController {

    /** 应用代号，取自 app.name，避免在代码里重复写死品牌名 */
    @Value("${app.name:wayfare}")
    private String appName;

    /** 应用版本，取自 app.version */
    @Value("${app.version:unknown}")
    private String appVersion;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        // 用 LinkedHashMap 固定字段顺序，方便肉眼核对与脚本断言
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("app", appName);
        data.put("version", appVersion);
        data.put("time", LocalDateTime.now().toString());
        return Result.success(data);
    }
}
