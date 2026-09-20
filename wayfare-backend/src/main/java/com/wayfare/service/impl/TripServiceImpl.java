package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;
import com.wayfare.entity.TripItem;
import com.wayfare.mapper.TripDayMapper;
import com.wayfare.mapper.TripItemMapper;
import com.wayfare.mapper.TripMapper;
import com.wayfare.service.TripService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 行程服务实现 —— P3-F 补齐 P2-B 预留的实现。
 *
 * <p>P2-B 只定了签名、留了三个 Mapper 的装配，方法体一律抛
 * {@link UnsupportedOperationException}；P3-F 的结果组装落在本实现上。
 *
 * <p><b>本类是无笔直业务逻辑的「纯数据层」</b>：编排（分天/选点/校验/回喂重排）、
 * 事实补全（Step6 enrichRoutes）都在 {@code TripOrchestrator} 里，
 * 这里只负责「把已经组装好的聚合落进 trip / trip_day / trip_item，并按 id 取回」。
 * 这样数据层不依赖编排细节，将来改编排策略不用动表结构。
 */
@Service
public class TripServiceImpl implements TripService {

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
        Trip trip = new Trip();
        trip.setUserId(userId);
        trip.setRawInput(rawInput);
        trip.setStatus(0);
        tripMapper.insert(trip);
        return trip;
    }

    @Override
    @Transactional
    public Trip saveFullTrip(Trip trip, List<TripDay> dayPlans) {
        if (trip.getId() == null) {
            tripMapper.insert(trip);
        } else {
            // 已有 id：整份重存 —— 更新主表，删掉旧子表后重建。
            // 改行程是整份替换而不是增量，删除子表是这条语义的落点。
            tripMapper.updateById(trip);
            tripDayMapper.delete(new LambdaQueryWrapper<TripDay>()
                    .eq(TripDay::getTripId, trip.getId()));
            tripItemMapper.delete(new LambdaQueryWrapper<TripItem>()
                    .eq(TripItem::getTripId, trip.getId()));
        }

        if (dayPlans != null) {
            for (TripDay day : dayPlans) {
                day.setTripId(trip.getId());
                tripDayMapper.insert(day);
                if (day.getItems() != null) {
                    for (TripItem item : day.getItems()) {
                        item.setTripId(trip.getId());
                        item.setDayIndex(day.getDayIndex());
                        tripItemMapper.insert(item);
                    }
                }
            }
        }
        return trip;
    }

    @Override
    public Trip getDetail(Long tripId, Long userId) {
        Trip trip = tripMapper.selectOne(new LambdaQueryWrapper<Trip>()
                .eq(Trip::getId, tripId)
                .eq(Trip::getUserId, userId));
        if (trip == null) {
            return null;
        }
        List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
                .eq(TripDay::getTripId, tripId)
                .orderByAsc(TripDay::getDayIndex));
        for (TripDay day : days) {
            List<TripItem> items = tripItemMapper.selectList(new LambdaQueryWrapper<TripItem>()
                    .eq(TripItem::getTripId, tripId)
                    .eq(TripItem::getDayIndex, day.getDayIndex())
                    .orderByAsc(TripItem::getSeq));
            day.setItems(items);
        }
        trip.setDayPlans(days);
        return trip;
    }

    @Override
    public IPage<Trip> pageMy(Long userId, Integer pageNum, Integer pageSize) {
        return tripMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Trip>()
                        .eq(Trip::getUserId, userId)
                        .orderByDesc(Trip::getCreatedAt));
    }

    @Override
    public void logicDelete(Long tripId, Long userId) {
        if (getDetail(tripId, userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "行程不存在");
        }
        // Trip.deleted 带 @TableLogic，deleteById 落库为 UPDATE deleted=1（逻辑删除）
        tripMapper.deleteById(tripId);
    }

    @Override
    public void updateItemOrder(Long tripId, Integer dayIndex, List<Long> itemIdsInOrder) {
        if (itemIdsInOrder == null) {
            return;
        }
        int seq = 0;
        for (Long itemId : itemIdsInOrder) {
            TripItem item = tripItemMapper.selectById(itemId);
            if (item != null && tripId.equals(item.getTripId()) && dayIndex.equals(item.getDayIndex())) {
                item.setSeq(seq++);
                tripItemMapper.updateById(item);
            }
        }
    }
}