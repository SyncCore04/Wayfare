package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wayfare.dto.TravelProfileDTO;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.mapper.UserTravelProfileMapper;
import com.wayfare.service.TravelProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户旅行偏好画像服务实现（P2-A）。
 *
 * <p><b>本类最关键的一个决定：upsert 用「删了重插」而不是 {@code updateById}。</b>
 * 原因是 MyBatis-Plus 的 {@code updateById} 默认策略是 {@code NOT_NULL} ——
 * <b>值为 null 的字段会被静默跳过，根本不进 SQL</b>。而本表的可空列
 * （{@code pace} / {@code budgetLevel} / {@code walkLimitKm}）恰恰需要能被写成 null：
 * 用户把「单日步行上限」清空时，我们就要真的把它置为 NULL。
 * 若用 {@code updateById}，这个清空操作会**看起来成功但实际没生效**，
 * 下一次读出来还是旧值 —— 这类「静默不生效」比报错难查得多。
 * （项目里 {@code poi_cache} 的 upsert 因为同样的原因也改成了删了重插。）
 *
 * <p>删了重插的代价是主键会变。本表没有任何外键引用它
 * （{@code trip.profile_used} 只是个布尔标记），所以这个代价可以接受；
 * {@code created_at} 会被显式带过去，保证「首次创建时间」不因编辑而漂移。
 *
 * <p>另一个约定：<b>空串统一归一化成 null</b>。「没填」只有一种表示，
 * 下游渲染时就不必同时判 {@code null} 和 {@code ""} 两遍。
 */
@Service
public class TravelProfileServiceImpl implements TravelProfileService {

    private static final Logger log = LoggerFactory.getLogger(TravelProfileServiceImpl.class);

    /** 隐私开关的默认值：允许 AI 使用（与建表语句的 DEFAULT 1 保持一致） */
    private static final int DEFAULT_ALLOW_AI_USE = 1;

    private final UserTravelProfileMapper userTravelProfileMapper;

    public TravelProfileServiceImpl(UserTravelProfileMapper userTravelProfileMapper) {
        this.userTravelProfileMapper = userTravelProfileMapper;
    }

    @Override
    public UserTravelProfile getByUserId(Long userId) {
        UserTravelProfile profile = selectByUserId(userId);
        if (profile != null) {
            return profile;
        }
        // 不存在时返回「空画像」而不是 null：接口层可直接序列化，
        // 前端永远拿到同一种结构（手册要求「返回空对象，不报 404」）
        UserTravelProfile empty = new UserTravelProfile();
        empty.setUserId(userId);
        empty.setAllowAiUse(DEFAULT_ALLOW_AI_USE);
        return empty;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserTravelProfile save(Long userId, TravelProfileDTO dto) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        UserTravelProfile existing = selectByUserId(userId);

        UserTravelProfile entity = new UserTravelProfile();
        entity.setUserId(userId);
        entity.setCuisines(normalizeCsv(dto.getCuisines()));
        entity.setFlavors(normalizeCsv(dto.getFlavors()));
        entity.setTaboos(normalizeCsv(dto.getTaboos()));
        entity.setTravelStyles(normalizeCsv(dto.getTravelStyles()));
        entity.setPace(dto.getPace());
        entity.setBudgetLevel(dto.getBudgetLevel());
        entity.setCompanions(trimToNull(dto.getCompanions()));
        entity.setWalkLimitKm(dto.getWalkLimitKm());
        entity.setHotelPref(trimToNull(dto.getHotelPref()));
        entity.setNotes(trimToNull(dto.getNotes()));
        // 不传按「允许」处理：隐私开关的默认值必须是开放的，
        // 否则用户填了画像却因为漏传字段而永远用不上
        entity.setAllowAiUse(dto.getAllowAiUse() != null ? dto.getAllowAiUse() : DEFAULT_ALLOW_AI_USE);

        if (existing == null) {
            userTravelProfileMapper.insert(entity);
            log.info("用户画像首次创建: userId={}", userId);
        } else {
            // 保留首次创建时间，避免编辑一次就刷新一次
            entity.setCreatedAt(existing.getCreatedAt());
            // 先删后插：只有这样才能把 null 真正写进可空列（见类注释）
            userTravelProfileMapper.deleteById(existing.getId());
            userTravelProfileMapper.insert(entity);
            log.info("用户画像已整体替换: userId={}, 旧主键={}, 新主键={}",
                    userId, existing.getId(), entity.getId());
        }
        return entity;
    }

    // ==================== 内部 ====================

    private UserTravelProfile selectByUserId(Long userId) {
        LambdaQueryWrapper<UserTravelProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserTravelProfile::getUserId, userId);
        return userTravelProfileMapper.selectOne(wrapper);
    }

    /**
     * 逗号分隔字段的归一化：逐段去空白、丢掉空段、去重，再重新用逗号拼回。
     *
     * <p>为什么要做这一步：这些字段最终会被渲染成「晋菜、面食」这样的中文列表，
     * 用户手输 "晋菜, ,面食" 或 "晋菜,,面食" 时，不归一化就会渲染出
     * 「晋菜、、面食」这种带空项的结果。去重则是为了让 overrides 的
     * 「与画像合并」逻辑天然不会产生重复项。
     */
    private String normalizeCsv(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String segment : raw.split("[,，]")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /** 去空白；空串归一化成 null（「没填」只有一种表示） */
    private String trimToNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
