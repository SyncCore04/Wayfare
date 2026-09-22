package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.TripPlanRequest;
import com.wayfare.dto.TripPlanResponse;
import com.wayfare.entity.Trip;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.security.UserContext;
import com.wayfare.service.TripService;
import com.wayfare.service.TripStreamService;
import com.wayfare.trip.TripOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程域接口（P3-F）。
 *
 * <p>所有接口都是<b>登录用户私有数据</b>，归属校验由 {@link TripService} 的内部实现保证
 * （签名带 userId，避免「换个 id 就看别人的行程」的越权漏洞）。
 *
 * <p>核心入口是 {@code POST /api/trip/plan/sync} —— 一次同步规划，
 * 内部串起整条七步管线并落库。其余是行程的查询 / 重排 / 删除。
 */
@RestController
@RequestMapping("/trip")
public class TripController {

    private static final Pattern DAY_INDEX = Pattern.compile("第\\s*(\\d+)\\s*天");

    private final TripOrchestrator orchestrator;
    private final TripService tripService;
    private final TripStreamService tripStreamService;

    public TripController(TripOrchestrator orchestrator, TripService tripService,
                          TripStreamService tripStreamService) {
        this.orchestrator = orchestrator;
        this.tripService = tripService;
        this.tripStreamService = tripStreamService;
    }

    /**
     * 同步规划：{@code rawInput} 一句话 → 完整行程落库并返回。
     */
    @PostMapping("/plan/sync")
    public Result<TripPlanResponse> planSync(@RequestBody TripPlanRequest req) {
        if (req == null || !StringUtils.hasText(req.getRawInput())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "行程需求不能为空");
        }
        Long userId = UserContext.getUserId();
        boolean useProfile = req.getUseProfile() == null || req.getUseProfile();
        ProfileOverrides overrides = toOverrides(req);

        TripOrchestrator.OrchestrationOutcome o =
                orchestrator.orchestrate(userId, req.getRawInput().trim(), useProfile, overrides);

        TripPlanResponse resp = new TripPlanResponse();
        resp.setTripId(o.tripId());
        resp.setIntent(o.intent());
        resp.setShortage(o.pool() != null && o.pool().isShortage());
        resp.setShortageHint(o.pool() == null ? null : o.pool().getShortageHint());
        resp.setValidation(o.report() != null && !o.report().passed()
                ? new ArrayList<>(o.report().violations()) : null);
        resp.setTrip(o.draft());
        resp.setComposeError(o.composeError());
        resp.setMeta(o.meta());
        return Result.success(resp);
    }

    /**
     * 流式规划（P4-A）：请求体与 {@link #planSync} 完全相同，响应是 {@code text/event-stream}。
     *
     * <p>为什么需要它：同步接口实测一次 194~507 秒（主力模型屡次 90 秒超时后降级），
     * 浏览器与 axios 的默认超时都等不到。流式版先推 {@code stage} 进度、
     * 再推 {@code itinerary} 骨架（十几秒即可渲染），最后用 {@code delta} 填文案。
     *
     * <p>{@code userId} 必须在<b>请求线程里</b>取：生成跑在独立线程池，
     * 那里读不到 {@code UserContext} 的 ThreadLocal。
     */
    @PostMapping(value = "/plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter planStream(@RequestBody TripPlanRequest req) {
        if (req == null || !StringUtils.hasText(req.getRawInput())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "行程需求不能为空");
        }
        Long userId = UserContext.getUserId();
        boolean useProfile = req.getUseProfile() == null || req.getUseProfile();
        return tripStreamService.start(userId, req.getRawInput().trim(), useProfile, toOverrides(req));
    }

    /**
     * 行程详情（本人可见）。
     */
    @GetMapping("/{id}")
    public Result<Trip> detail(@PathVariable Long id) {
        Trip trip = tripService.getDetail(id, UserContext.getUserId());
        if (trip == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "行程不存在");
        }
        return Result.success(trip);
    }

    /**
     * 我的行程分页列表。
     */
    @GetMapping("/my")
    public Result<IPage<Trip>> my(@RequestParam(defaultValue = "1") Integer pageNum,
                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(tripService.pageMy(UserContext.getUserId(), pageNum, pageSize));
    }

    /**
     * 重排：只重排「第 N 天」所在范围。从 {@code feedback} 里解析出 dayIndex，
     * 以这条行程的意图 + 带 dayIndex 的反馈触发一次针对性重新编排。
     */
    @PostMapping("/{id}/replan")
    public Result<TripPlanResponse> replan(@PathVariable Long id,
                                           @RequestBody ReplanRequest body) {
        if (body == null || !StringUtils.hasText(body.getFeedback())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请给出重排意见，如「第2天太赶」");
        }
        Long userId = UserContext.getUserId();
        Integer dayIndex = parseDayIndex(body.getFeedback());

        TripOrchestrator.OrchestrationOutcome o =
                orchestrator.replan(userId, id, scopedFeedback(body.getFeedback(), dayIndex));

        TripPlanResponse resp = new TripPlanResponse();
        resp.setTripId(o.tripId());
        resp.setIntent(o.intent());
        resp.setShortage(o.pool() != null && o.pool().isShortage());
        resp.setShortageHint(o.pool() == null ? null : o.pool().getShortageHint());
        resp.setValidation(o.report() != null && !o.report().passed()
                ? new ArrayList<>(o.report().violations()) : null);
        resp.setTrip(o.draft());
        resp.setComposeError(o.composeError());
        resp.setMeta(o.meta());
        return Result.success(resp);
    }

    /**
     * 逻辑删除行程（置 {@code trip.deleted}）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tripService.logicDelete(id, UserContext.getUserId());
        return Result.success();
    }

    // ==================== 私有 ====================

    private ProfileOverrides toOverrides(TripPlanRequest req) {
        if (req.getOverrides() == null) {
            return new ProfileOverrides();
        }
        List<String> taboos = req.getOverrides().getTaboos() == null
                ? null : new ArrayList<>(req.getOverrides().getTaboos());
        return new ProfileOverrides(taboos, req.getOverrides().getPace());
    }

    private Integer parseDayIndex(String feedback) {
        if (feedback == null) {
            return null;
        }
        Matcher m = DAY_INDEX.matcher(feedback);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 把 dayIndex 显式织进反馈，让下游 Composer 明确知道要改哪一天 */
    private String scopedFeedback(String feedback, Integer dayIndex) {
        if (dayIndex == null) {
            return feedback;
        }
        return "重点调整第 " + dayIndex + " 天的安排：" + feedback.replaceAll("第\\s*\\d+\\s*天", "").trim();
    }

    /** 重排请求体 */
    public static class ReplanRequest {
        private String feedback;

        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
    }
}