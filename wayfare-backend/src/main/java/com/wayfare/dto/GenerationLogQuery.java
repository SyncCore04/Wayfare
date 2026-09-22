package com.wayfare.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成明细的查询条件（P6-B · 看板表格）。
 *
 * <p>做成 DTO 而不是一路传 {@code Map}：筛选条件有 5 个，用 Map 的话
 * 「键名写错」只能等到运行时才发现，而且 Mapper Provider 与 Controller 之间
 * 没有一处能说清「到底支持哪些筛选」。
 *
 * <p>几个刻意的约定：
 * <ul>
 *   <li><b>空白字符串一律等于「没填」</b>：前端清空输入框提交的是 {@code ""}，
 *       若当成筛选值就会变成 {@code LIKE '%%'}（看着没筛，其实是全表扫）。</li>
 *   <li><b>窗口是左闭右闭</b>（含 {@code to} 当天全天），与 stats 接口口径一致。</li>
 *   <li>{@code page} 从 1 起、{@code size} 上限 100：后台看板没有理由一次拉一万行。</li>
 * </ul>
 */
public class GenerationLogQuery {

    private static final int MAX_PAGE_SIZE = 100;

    private LocalDate from;
    private LocalDate to;
    private Boolean success;
    private String stage;
    private String model;
    private String destination;
    private String mapMode;
    private Integer page = 1;
    private Integer size = 20;

    /**
     * 转成 Mapper Provider 认的键值对。
     *
     * <p><b>为 null 的键直接不放进去</b>（而不是放 null 值）：
     * Provider 里用 {@code q.get(x) != null} 判断是否追加条件，
     * 同时 SQL 里也不会引用到未追加的占位符。
     */
    public Map<String, Object> toParamMap() {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end : from;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", start.atStartOfDay());
        p.put("to", end.plusDays(1).atStartOfDay());
        if (success != null) {
            // TINYINT 列，用 0/1 而不是 true/false：让比较留在整型域里
            p.put("success", success ? 1 : 0);
        }
        putIfNotBlank(p, "stage", stage);
        putIfNotBlank(p, "model", model);
        putIfNotBlank(p, "destination", destination);
        putIfNotBlank(p, "mapMode", mapMode);
        p.put("offset", (getPage() - 1) * getSize());
        p.put("size", getSize());
        return p;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    // ---------- getter / setter（带范围收敛，避免 -1 页或超大页把库拖垮） ----------

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getMapMode() { return mapMode; }
    public void setMapMode(String mapMode) { this.mapMode = mapMode; }

    public int getPage() { return page == null || page < 1 ? 1 : page; }
    public void setPage(Integer page) { this.page = page; }

    public int getSize() {
        if (size == null || size < 1) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public void setSize(Integer size) { this.size = size; }
}
