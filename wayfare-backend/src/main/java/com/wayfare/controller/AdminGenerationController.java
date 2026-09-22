package com.wayfare.controller;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.ExternalCallLogService;
import com.wayfare.dto.GenerationLogQuery;
import com.wayfare.security.UserContext;
import com.wayfare.service.AiLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 生成统计接口（P4-C）—— 后台看板的数据源，P6 直接对接。
 *
 * <p>数据全部来自 {@code ai_generation_log}（必要时 join {@code trip} 取地图模式），
 * <b>不做二次加工</b>：这个接口的价值就是「返回的数字与日志表逐行相加一致」，
 * 任何「顺手修一下」都会让它失去作为证据的资格。
 *
 * <p>路径只写应用内路径 {@code /admin/generation} —— {@code server.servlet.context-path}
 * 已经是 {@code /api}，再写一遍会变成 {@code /api/api/...} 并被当成静态资源返回 500
 * （P3-F 联调踩过这个坑，见 MEMORY.md）。
 */
@RestController
@RequestMapping("/admin/generation")
public class AdminGenerationController {

    /** 默认统计窗口：最近 7 天（含今天） */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** 趋势图最长可查的窗口：再长就没人看了，也白扫一遍日志表 */
    private static final int MAX_TREND_DAYS = 90;

    /** 每页上限：后台看板没有理由一次拉一万行 */
    private static final int MAX_PAGE_SIZE = 100;

    private final AiLogService aiLogService;
    private final ExternalCallLogService externalCallLogService;

    public AdminGenerationController(AiLogService aiLogService,
                                     ExternalCallLogService externalCallLogService) {
        this.aiLogService = aiLogService;
        this.externalCallLogService = externalCallLogService;
    }

    /**
     * 生成统计总览：{@code GET /api/admin/generation/stats?from=&to=}
     *
     * <p>两个参数都可省略：{@code to} 默认今天，{@code from} 默认 {@code to} 往前 7 天。
     * 日期格式 {@code yyyy-MM-dd}，<b>窗口是左闭右闭</b>（含 {@code to} 当天全天）——
     * 接口层说「到 9 月 22 日」就应该包含 22 日，右开区间会让用户少看一天数据。
     *
     * @return 见 {@link AiLogService#generationStats} 的返回结构说明
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        checkAdmin();

        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_WINDOW_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        return Result.success(aiLogService.generationStats(start, end));
    }

    /**
     * 某条行程的 token 与成本拆解：{@code GET /api/admin/generation/trips/{tripId}/breakdown}
     *
     * <p>手册 P4-C 验收 4 要的「一次完整生成的 token 与成本拆解表」就是它的输出：
     * 分阶段列出 token 与耗时，并给出这次生成的总 token 与预估成本。
     *
     * <p>放在 admin 域而不是 trip 域是有意的：token 与单价属于运维信息，
     * 不该出现在普通用户自己的行程详情响应里。
     */
    @GetMapping("/trips/{tripId}/breakdown")
    public Result<Map<String, Object>> tripBreakdown(@PathVariable Long tripId) {
        checkAdmin();
        return Result.success(aiLogService.breakdownByTrip(tripId));
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    // ==================== P6-B 监控看板 ====================

    /**
     * 每日趋势：{@code GET /api/admin/generation/trend?days=7}
     *
     * <p>给看板折线图用（生成次数 + 成本，双 Y 轴）。窗口<b>含今天</b>，往前数 {@code days} 天。
     *
     * <p>为什么不让前端按天循环调 {@code /stats}：7 个请求等于把同一组聚合查询跑 7 遍，
     * 而且多次请求之间数据可能变化，图上会出现自相矛盾的点。
     */
    @GetMapping("/trend")
    public Result<Map<String, Object>> trend(@RequestParam(required = false) Integer days) {
        checkAdmin();
        int window = days == null ? DEFAULT_WINDOW_DAYS : Math.min(Math.max(days, 1), MAX_TREND_DAYS);
        LocalDate end = LocalDate.now();
        return Result.success(aiLogService.dailyTrend(end.minusDays(window - 1L), end));
    }

    /**
     * 生成明细分页：{@code GET /api/admin/generation/logs?from=&to=&success=&stage=&model=&destination=&mapMode=&page=&size=}
     *
     * <p>按阶段逐行返回（一次生成占 5~7 行），并 join 出 trip 的目的地/天数/地图模式 ——
     * 「筛目的地」「筛 mapMode」正是排查「某个地方为什么排不出来」时最先用的两个条件。
     *
     * <p>展开某一行想看它所属那次生成的全部阶段时，用
     * {@code GET /admin/generation/trips/{tripId}/breakdown}（P4-C 已有）。
     */
    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String mapMode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        checkAdmin();

        // 与 /stats 保持一致：开始晚于结束时直接 400，而不是返回一个「看起来正常」的空列表
        // —— 空列表会让调用方以为「这个区间真的没数据」
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }

        GenerationLogQuery query = new GenerationLogQuery();
        query.setFrom(from);
        query.setTo(to);
        query.setSuccess(success);
        query.setStage(stage);
        query.setModel(model);
        query.setDestination(destination);
        query.setMapMode(mapMode);
        query.setPage(page);
        query.setSize(size == null ? null : Math.min(size, MAX_PAGE_SIZE));
        return Result.success(aiLogService.pageStages(query));
    }

    /**
     * 外部调用日志分页：{@code GET /api/admin/generation/external-calls?connector=&page=&size=}
     *
     * <p>放在 generation 域下而不是另开一个 Controller：它是同一块看板的第二个页签，
     * 路径跟着看板走，前端一眼能看出归属。
     *
     * <p>⚠️ {@code request_summary} 在<b>写入时</b>就已脱敏（{@code MaskUtil.sanitize}，
     * 先脱敏再截断），所以这里不需要、也不应该再做一次处理 —— 做二次处理会掩盖
     * 「写入时漏脱敏」这类真问题。看板页签同时也是这个安全机制的可视化验收点。
     */
    @GetMapping("/external-calls")
    public Result<Map<String, Object>> externalCalls(
            @RequestParam(required = false) String connector,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        checkAdmin();
        int p = page == null ? 1 : Math.max(page, 1);
        int s = size == null ? 20 : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return Result.success(externalCallLogService.page(
                connector == null || connector.isBlank() ? null : connector.trim(), p, s));
    }
}
