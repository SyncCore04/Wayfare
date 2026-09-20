package com.wayfare.controller;

import com.wayfare.common.result.Result;
import com.wayfare.dto.TravelProfileDTO;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.security.UserContext;
import com.wayfare.service.TravelProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户旅行偏好画像接口（P2-A）。
 *
 * <p><b>真实地址是 {@code /api/profile/travel}</b> —— 应用配置了 context-path {@code /api}，
 * 这里的 {@code @RequestMapping("/profile")} 是应用内路径。
 *
 * <p><b>为什么两个接口都不带 userId 参数</b>：画像只能读写「当前登录用户」自己那一份，
 * userId 一律取自 {@code UserContext}（拦截器已保证进到这里必定已登录）。
 * 若把 userId 做成路径参数，就得再写一遍越权校验，而漏写一次就是数据泄露。
 * 从接口形状上杜绝，比事后加校验可靠。
 *
 * <p>路径不在 {@code JwtInterceptor.PUBLIC_PATHS} 白名单里，所以未登录访问会拿到真 HTTP 401。
 */
@RestController
@RequestMapping("/profile")
public class TravelProfileController {

    private final TravelProfileService travelProfileService;

    public TravelProfileController(TravelProfileService travelProfileService) {
        this.travelProfileService = travelProfileService;
    }

    /**
     * 获取当前用户的旅行偏好画像。
     *
     * <p>从未填写过时返回「空画像」（userId 已填、其余字段为空），
     * <b>不返回 404</b> —— 「还没填」是正常状态而不是错误，
     * 前端据此直接渲染空表单即可。
     */
    @GetMapping("/travel")
    public Result<UserTravelProfile> getTravelProfile() {
        Long userId = UserContext.getUserId();
        return Result.success(travelProfileService.getByUserId(userId));
    }

    /**
     * 保存当前用户的旅行偏好画像（存在则更新，不存在则插入）。
     *
     * <p>返回保存后的完整画像，前端可直接用它刷新表单
     * （不必再发一次 GET）。
     */
    @PutMapping("/travel")
    public Result<UserTravelProfile> saveTravelProfile(@Valid @RequestBody TravelProfileDTO dto) {
        Long userId = UserContext.getUserId();
        return Result.success("画像已保存", travelProfileService.save(userId, dto));
    }
}
