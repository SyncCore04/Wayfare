package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.WorkDTO;
import com.wayfare.entity.Trip;
import com.wayfare.entity.Work;
import com.wayfare.security.UserContext;
import com.wayfare.service.WorkService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/works")
public class WorkController {

    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @PostMapping
    public Result<Work> create(@Valid @RequestBody WorkDTO workDTO) {
        return Result.success(workService.create(workDTO, requireLogin()));
    }

    @PutMapping("/{id}")
    public Result<Work> update(@PathVariable Long id, @Valid @RequestBody WorkDTO workDTO) {
        // 仅作者或管理员可改，权限判断在 Service 里（需要先查出作品的 userId）
        return Result.success(workService.update(id, workDTO, requireLogin(), UserContext.isAdmin()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        workService.delete(id, requireLogin(), UserContext.isAdmin());
        return Result.success();
    }

    /**
     * 作品详情（公开，浏览量 +1）。
     * 该路径在 WebMvcConfig 白名单里，未登录也能看。
     */
    @GetMapping("/{id}")
    public Result<Work> getById(@PathVariable Long id) {
        workService.incrementViewCount(id);
        return Result.success(workService.getById(id));
    }

    /**
     * 攻略关联的「完整行程」（P5-C · 详情页的时间轴区块）。
     *
     * <p>公开路径（与 {@code /{id}} 同级）—— 行程随攻略一起公开，这正是「发布为攻略」的语义。
     * 未发布/待审核的攻略拿不到行程；纯图文攻略返回 {@code data: null}（正常状态，不是 404）。
     *
     * <p>⚠️ 路径必须在 {@code JwtInterceptor.PUBLIC_PATHS} 里单独加一条：
     * 白名单用的是 PathPattern 完整匹配，{@code /works/{id:[0-9]+}} 匹配不到 {@code /works/1/trip}。
     */
    @GetMapping("/{id}/trip")
    public Result<Trip> getTripOfWork(@PathVariable Long id) {
        return Result.success(workService.getTripByWorkId(id));
    }

    /**
     * 作品分页。
     *
     * @param destination 目的地筛选（攻略特有，精确匹配）
     * @param tripDays    行程天数筛选（攻略特有，精确匹配）
     * @param minTripDays 行程天数下限（首页「更多」档 = ≥N 天）；与 tripDays 互斥
     */
    @GetMapping("/page")
    public Result<IPage<Work>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) Integer tripDays,
            @RequestParam(required = false) Integer minTripDays) {
        return Result.success(workService.page(pageNum, pageSize, userId, categoryId,
                status, keyword, destination, tripDays, minTripDays));
    }

    @GetMapping("/my")
    public Result<IPage<Work>> myWorks(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer status) {
        Long userId = requireLogin();
        return Result.success(workService.page(pageNum, pageSize, userId, null,
                status, null, null, null, null));
    }

    /**
     * 更新作品状态（管理员审核用）。
     * 该路径不在白名单里，未登录会在拦截器就返回 401；这里只管「已登录但不是管理员」→ 403。
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        workService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 取当前登录用户 id，未登录直接 401。
     *
     * <p>为什么需要它：{@code /works/{id:[0-9]+}} 为了让未登录用户能看详情而被放进了白名单，
     * 而白名单是按路径匹配、不区分 HTTP 方法，于是未登录的 PUT/DELETE 同一路径也会进到这里。
     * 这个方法就是补在方法维度的那道闸，避免出现 userId 为 null 一路传到 SQL 里。
     */
    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }
}
