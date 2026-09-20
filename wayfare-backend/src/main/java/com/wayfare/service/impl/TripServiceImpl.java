package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;
import com.wayfare.mapper.TripDayMapper;
import com.wayfare.mapper.TripItemMapper;
import com.wayfare.mapper.TripMapper;
import com.wayfare.service.TripService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 行程服务实现 —— <b>P2-B 阶段只有签名，没有业务逻辑</b>。
 *
 * <p>手册对 P2-B 的要求是「Service 接口方法可以先留空实现，但签名要定好，
 * 不要写业务逻辑」。本类就是那个「留空实现」。
 *
 * <p><b>为什么是抛异常而不是 {@code return null}</b>：这是有意选的。
 * 「留空」有两种写法，代价完全不同：
 * <ul>
 *   <li>{@code return null} —— 调用方拿到 null 后可能一路传到前端，
 *       变成「行程详情空白」这种<b>看起来像 bug 但其实是没实现</b>的现象，
 *       排查方向会被带偏；更糟的是 P3 可能忘了补实现，而测试仍然全绿；</li>
 *   <li>抛 {@link UnsupportedOperationException} —— 谁提前调用了就当场炸，
 *       异常信息里直接写明「属 P3」。<b>把一个「沉默的坑」变成一个「响亮的提示」。</b></li>
 * </ul>
 * 同理，这里的异常不是 {@code BusinessException}：它不是业务错误，
 * 不该被全局异常处理器包装成「操作失败」这种用户可见的提示 ——
 * 它只应该在开发/测试阶段出现，冒泡成 500 反而更醒目。
 *
 * <p>三个 Mapper 已经在构造函数里注入好了，P3 补实现时直接写方法体即可，
 * 不需要再动装配。
 */
@Service
public class TripServiceImpl implements TripService {

    /** 统一的未实现提示：点明「归属哪一块」，避免排查时四处翻文档 */
    private static final String TODO_HINT =
            "尚未实现：本方法属于 P3（七步行程编排管线）的交付范围，P2-B 只负责定签名与建表";

    private final TripMapper tripMapper;
    private final TripDayMapper tripDayMapper;
    private final TripItemMapper tripItemMapper;

    public TripServiceImpl(TripMapper tripMapper, TripDayMapper tripDayMapper, TripItemMapper tripItemMapper) {
        this.tripMapper = tripMapper;
        this.tripDayMapper = tripDayMapper;
        this.tripItemMapper = tripItemMapper;
    }

    @Override
    public Trip createDraft(Long userId, String rawInput) {
        throw notImplemented("createDraft");
    }

    @Override
    public Trip saveFullTrip(Trip trip, List<TripDay> dayPlans) {
        throw notImplemented("saveFullTrip");
    }

    @Override
    public Trip getDetail(Long tripId, Long userId) {
        throw notImplemented("getDetail");
    }

    @Override
    public IPage<Trip> pageMy(Long userId, Integer pageNum, Integer pageSize) {
        throw notImplemented("pageMy");
    }

    @Override
    public void logicDelete(Long tripId, Long userId) {
        throw notImplemented("logicDelete");
    }

    @Override
    public void updateItemOrder(Long tripId, Integer dayIndex, List<Long> itemIdsInOrder) {
        throw notImplemented("updateItemOrder");
    }

    private UnsupportedOperationException notImplemented(String method) {
        return new UnsupportedOperationException("TripService." + method + " " + TODO_HINT);
    }
}
