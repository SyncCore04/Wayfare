package com.wayfare.controller;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.common.util.MaskUtil;
import com.wayfare.entity.SysConfig;
import com.wayfare.security.UserContext;
import com.wayfare.service.SysConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时配置的管理接口（P1-E 定稿）。
 *
 * <p><b>这里取代了 P1-A 临时加的 {@code /config/value}、{@code /config/group/*}</b>：
 * 手册把配置管理归到 /admin 命名空间下，且要求按组返回、支持单键写入。
 * 旧的 SysConfigController 已删除，避免出现两套配置接口。
 *
 * <p>权限：全部需要登录 + 管理员（/admin 前缀不在公开白名单里）。
 * 敏感值（如 map.baidu.ak）回显一律掩码 —— 手册 12.3 的硬性要求。
 */
@RestController
@RequestMapping("/admin")
public class AdminConfigController {

    private final SysConfigService sysConfigService;

    public AdminConfigController(SysConfigService sysConfigService) {
        this.sysConfigService = sysConfigService;
    }

    /**
     * 全部配置，按 group 分组返回（后台配置页的数据源）。
     * 敏感值已掩码。
     */
    @GetMapping("/configs")
    public Result<Map<String, List<Map<String, Object>>>> allConfigs() {
        checkAdmin();
        List<SysConfig> list = sysConfigService.listByGroup(null);
        return Result.success(groupAndMask(list));
    }

    /**
     * 按组读取，例如 GET /admin/configs/group/llm。
     */
    @GetMapping("/configs/group/{group}")
    public Result<Map<String, List<Map<String, Object>>>> byGroup(@PathVariable String group) {
        checkAdmin();
        List<SysConfig> list = sysConfigService.listByGroup("all".equalsIgnoreCase(group) ? null : group);
        return Result.success(groupAndMask(list));
    }

    /** 读单个配置键（敏感值掩码） */
    @GetMapping("/configs/{key}")
    public Result<Map<String, Object>> byKey(@PathVariable String key) {
        checkAdmin();
        Map<String, Object> data = new LinkedHashMap<>();
        String raw = sysConfigService.get(key);
        data.put("key", key);
        data.put("value", MaskUtil.maskConfigValue(key, raw));
        data.put("masked", MaskUtil.isSecretKey(key));
        data.put("exists", raw != null);
        return Result.success(data);
    }

    /**
     * 写单个配置键，立即生效（内部会清 sys_config 的 Redis 缓存）。
     */
    @PutMapping("/configs/{key}")
    public Result<Void> setOne(@PathVariable String key, @RequestBody Map<String, String> body) {
        checkAdmin();
        String value = body == null ? null : body.get("value");
        sysConfigService.set(key, value, UserContext.getUserId());
        return Result.success();
    }

    /**
     * 一键修改大模型相关开关。
     *
     * <p>注意：{@code model} 不在这里支持 —— 模型名是 L1 启动期配置，
     * 且每家厂商各有一个（llm.providers.glm.model / llm.providers.deepseek.model），
     * 单独一个「llm.model」键在降级链里没法落。要换模型改 application.yml 后重启。
     */
    @PostMapping("/config/llm")
    public Result<Map<String, Object>> setLlm(@RequestBody Map<String, String> body) {
        checkAdmin();
        Long operator = UserContext.getUserId();
        Map<String, Object> applied = new LinkedHashMap<>();
        List<String> ignored = new java.util.ArrayList<>();

        if (body.get("activeProvider") != null && !body.get("activeProvider").isBlank()) {
            sysConfigService.set("llm.active-provider", body.get("activeProvider").trim(), operator);
            applied.put("llm.active-provider", body.get("activeProvider").trim());
        }
        if (body.get("fallbackOrder") != null && !body.get("fallbackOrder").isBlank()) {
            sysConfigService.set("llm.fallback-order", body.get("fallbackOrder").trim(), operator);
            applied.put("llm.fallback-order", body.get("fallbackOrder").trim());
        }
        if (body.get("model") != null && !body.get("model").isBlank()) {
            ignored.add("model（属 L1 启动期配置，改 application.yml 后重启生效）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applied", applied);
        data.put("ignored", ignored);
        return Result.success(data);
    }

    /**
     * 一键开关地图能力，立即生效（清缓存，不重启）。
     */
    @PostMapping("/config/map/enabled")
    public Result<Map<String, Object>> setMapEnabled(@RequestBody Map<String, Object> body) {
        checkAdmin();
        Object enabled = body == null ? null : body.get("enabled");
        if (enabled == null || (!(enabled instanceof Boolean) && !"true".equals(String.valueOf(enabled))
                && !"false".equals(String.valueOf(enabled)))) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "enabled 必须是 true 或 false");
        }
        String value = String.valueOf(enabled);
        sysConfigService.set("map.enabled", value, UserContext.getUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("map.enabled", value);
        data.put("note", "立即生效，无需重启");
        return Result.success(data);
    }

    /** 按组分组 + 掩码 */
    private Map<String, List<Map<String, Object>>> groupAndMask(List<SysConfig> list) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (SysConfig config : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configKey", config.getConfigKey());
            row.put("configValue", MaskUtil.maskConfigValue(config.getConfigKey(), config.getConfigValue()));
            row.put("masked", MaskUtil.isSecretKey(config.getConfigKey()));
            row.put("valueType", config.getValueType());
            row.put("description", config.getDescription());
            row.put("updatedAt", config.getUpdatedAt());
            grouped.computeIfAbsent(config.getGroupName(), k -> new java.util.ArrayList<>()).add(row);
        }
        return grouped;
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
