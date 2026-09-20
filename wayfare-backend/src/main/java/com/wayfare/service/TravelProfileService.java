package com.wayfare.service;

import com.wayfare.dto.TravelProfileDTO;
import com.wayfare.entity.UserTravelProfile;

/**
 * 用户旅行偏好画像服务（P2-A）。
 *
 * <p>只有两个动作：读、写。刻意不提供分页 / 列表 / 删除 ——
 * 画像是一人一行的私有数据，不存在「管理端浏览全部用户画像」这种场景，
 * 少一个接口就少一处越权面。
 */
public interface TravelProfileService {

    /**
     * 读取指定用户的画像。
     *
     * <p><b>约定：不存在时返回一个「空画像」而不是 null</b> ——
     * 即 userId 已填、其余字段全为空的实体。这样接口层不需要判 null 就能直接
     * 序列化返回（手册要求「不存在时返回空对象，不报 404」），
     * 前端拿到的永远是同一种结构，不必写两套渲染分支。
     *
     * <p>要判断「画像里到底有没有内容」，用 {@code ProfileRenderer.hasAnyContent(profile)}，
     * 不要用 {@code profile == null}。
     */
    UserTravelProfile getByUserId(Long userId);

    /**
     * 保存画像（存在则更新，不存在则插入）。
     *
     * <p><b>语义是「整体替换」而不是「增量合并」</b>：请求里没带的字段会被清空。
     * 理由与 PUT 的语义一致 —— 前端是拿一个完整表单提交的，
     * 若做成增量合并，用户「清掉忌口」这个操作就永远无法生效
     * （空值会被当成「这次没改」而忽略）。
     *
     * @return 保存后的画像（含回填的主键与时间戳）
     */
    UserTravelProfile save(Long userId, TravelProfileDTO dto);
}
