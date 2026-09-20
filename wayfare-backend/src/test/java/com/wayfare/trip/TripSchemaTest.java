package com.wayfare.trip;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;
import com.wayfare.entity.TripItem;
import com.wayfare.mapper.TripDayMapper;
import com.wayfare.mapper.TripItemMapper;
import com.wayfare.mapper.TripMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行程表族「表 ↔ 实体」与排序验证（P2-B 验收 2、3）。
 *
 * <p><b>为什么这里要真连库</b>：P2-B 是纯数据层，能验的只有两件事 ——
 * ① 实体字段与库列名对得上（列名写错要等真查询才炸）；
 * ② 「按 day + seq 正序查出」这条要求真的成立。
 * 两者都 mock 不掉。
 *
 * <p><b>关键手法：故意乱序插入。</b>如果按 0,1,2 的顺序插入再查，
 * 即便 SQL 里没有 ORDER BY，InnoDB 多半也会按主键顺序返回，
 * 测试会「假通过」。所以这里按 2,0,1 插入，再断言查出的是 0,1,2 ——
 * 只有真的带了 ORDER BY 才可能通过。
 *
 * <p>用 {@code @Transactional} 自动回滚，不留测试数据。
 */
@SpringBootTest
@Transactional
class TripSchemaTest {

    @Autowired private TripMapper tripMapper;
    @Autowired private TripDayMapper tripDayMapper;
    @Autowired private TripItemMapper tripItemMapper;

    @Test
    @DisplayName("验收2：三个 Mapper 的实体列映射全部可用，无 SQL 报错")
    void entityMappingIsClean() {
        // selectList 会按实体生成完整列名清单的 SELECT，任何「实体有、库里没有」的列都会抛异常
        assertNotNull(tripMapper.selectList(null));
        assertNotNull(tripDayMapper.selectList(null));
        assertNotNull(tripItemMapper.selectList(null));
    }

    @Test
    @DisplayName("验收3：插 1 条 trip + 2 条 trip_day + 5 条 trip_item，能按 day+seq 正序查出")
    void insertAndQueryInDaySeqOrder() {
        // ---------- 1 条 trip ----------
        Trip trip = new Trip();
        trip.setUserId(999_998L);
        trip.setTitle("泉州两日 · 古城与海丝");
        trip.setRawInput("周末想去泉州玩两天，喜欢古建筑，预算 800");
        trip.setDestination("泉州");
        trip.setDays(2);
        trip.setStartDate(LocalDate.of(2026, 9, 26));
        trip.setBudgetTotal(new BigDecimal("800.00"));
        trip.setBudgetMode(2);
        trip.setTransport("PUBLIC");
        trip.setCompanion("情侣");
        // 地图关闭时的正常状态：坐标为空、可信度为 ESTIMATED
        trip.setMapMode(TripItem.VERIFY_ESTIMATED);
        trip.setProfileUsed(1);
        trip.setStatus(1);
        trip.setModelName("glm-4-flash");
        trip.setGenerationRounds(1);

        assertEquals(1, tripMapper.insert(trip));
        assertNotNull(trip.getId(), "自增主键未回填");
        assertNotNull(trip.getCreatedAt(), "created_at 自动填充未生效");

        // ---------- 2 条 trip_day ----------
        for (int dayIndex = 2; dayIndex >= 1; dayIndex--) {
            TripDay day = new TripDay();
            day.setTripId(trip.getId());
            day.setDayIndex(dayIndex);
            day.setTitle(dayIndex == 1 ? "古城与开元寺" : "海丝与市井");
            day.setSummary("第 " + dayIndex + " 天的安排");
            assertEquals(1, tripDayMapper.insert(day));
        }

        // ---------- 5 条 trip_item（故意乱序插入：2,0,1 / 1,0）----------
        insertItem(trip.getId(), 1, 2, TripItem.TYPE_FOOD, "西街面线糊", "小吃", "12.00");
        insertItem(trip.getId(), 1, 0, TripItem.TYPE_SCENIC, "开元寺", "泉州市鲤城区西街", "40.00");
        insertItem(trip.getId(), 1, 1, TripItem.TYPE_SCENIC, "泉州府文庙", "泉州市鲤城区百源川池畔", "0.00");
        insertItem(trip.getId(), 2, 1, TripItem.TYPE_FOOD, "秉正堂石花膏", "泉州市鲤城区", "15.00");
        insertItem(trip.getId(), 2, 0, TripItem.TYPE_SCENIC, "洛阳桥", "泉州市洛江区", "0.00");

        // ---------- 按 day + seq 正序查出 ----------
        LambdaQueryWrapper<TripItem> wrapper = new LambdaQueryWrapper<TripItem>()
                .eq(TripItem::getTripId, trip.getId())
                .orderByAsc(TripItem::getDayIndex)
                .orderByAsc(TripItem::getSeq);
        List<TripItem> items = tripItemMapper.selectList(wrapper);

        assertEquals(5, items.size(), "应查出 5 条");
        List<String> actualOrder = new ArrayList<>();
        for (TripItem item : items) {
            actualOrder.add(item.getDayIndex() + "-" + item.getSeq() + ":" + item.getPoiName());
        }
        List<String> expectedOrder = List.of(
                "1-0:开元寺",
                "1-1:泉州府文庙",
                "1-2:西街面线糊",
                "2-0:洛阳桥",
                "2-1:秉正堂石花膏");
        assertEquals(expectedOrder, actualOrder,
                "必须按 dayIndex、seq 正序返回（乱序插入也要正确）");

        // 天数同样按 dayIndex 正序
        List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
                .eq(TripDay::getTripId, trip.getId())
                .orderByAsc(TripDay::getDayIndex));
        assertEquals(2, days.size());
        assertEquals(1, days.get(0).getDayIndex());
        assertEquals(2, days.get(1).getDayIndex());
    }

    @Test
    @DisplayName("数据诚信机制：verify_status / data_source 能落库，估算态的距离时长保持 NULL")
    void dataIntegrityFieldsRoundTrip() {
        Trip trip = new Trip();
        trip.setUserId(999_998L);
        trip.setTitle("数据诚信验证");
        trip.setStatus(0);
        tripMapper.insert(trip);

        // 估算态：距离/时长必须留 NULL，模糊表述写进 note
        TripItem estimated = new TripItem();
        estimated.setTripId(trip.getId());
        estimated.setDayIndex(1);
        estimated.setSeq(0);
        estimated.setItemType(TripItem.TYPE_SCENIC);
        estimated.setPoiName("某景点");
        estimated.setDistanceMeters(null);
        estimated.setDurationSeconds(null);
        estimated.setNote("步行约十几分钟");
        estimated.setVerifyStatus(TripItem.VERIFY_ESTIMATED);
        estimated.setDataSource(TripItem.SOURCE_LLM);
        tripItemMapper.insert(estimated);

        // 实测态：有真实距离与时长
        TripItem verified = new TripItem();
        verified.setTripId(trip.getId());
        verified.setDayIndex(1);
        verified.setSeq(1);
        verified.setItemType(TripItem.TYPE_SCENIC);
        verified.setPoiName("另一个景点");
        verified.setLng(new BigDecimal("118.5891234"));
        verified.setLat(new BigDecimal("24.9134567"));
        verified.setArriveTime(LocalTime.of(9, 30));
        verified.setLeaveTime(LocalTime.of(11, 0));
        verified.setStayMinutes(90);
        verified.setDistanceMeters(7433);
        verified.setDurationSeconds(1267);
        verified.setVerifyStatus(TripItem.VERIFY_VERIFIED);
        verified.setDataSource(TripItem.SOURCE_BAIDU);
        tripItemMapper.insert(verified);

        TripItem loadedEstimated = tripItemMapper.selectById(estimated.getId());
        assertNull(loadedEstimated.getDistanceMeters(), "估算态的距离必须是 NULL，不能编数字");
        assertNull(loadedEstimated.getDurationSeconds(), "估算态的时长必须是 NULL");
        assertEquals("步行约十几分钟", loadedEstimated.getNote());
        assertEquals(TripItem.VERIFY_ESTIMATED, loadedEstimated.getVerifyStatus());
        assertEquals(TripItem.SOURCE_LLM, loadedEstimated.getDataSource());

        TripItem loadedVerified = tripItemMapper.selectById(verified.getId());
        assertEquals(7433, loadedVerified.getDistanceMeters());
        assertEquals(1267, loadedVerified.getDurationSeconds());
        assertEquals(TripItem.VERIFY_VERIFIED, loadedVerified.getVerifyStatus());
        assertEquals(TripItem.SOURCE_BAIDU, loadedVerified.getDataSource());
        assertEquals(0, loadedVerified.getArriveTime().compareTo(LocalTime.of(9, 30)),
                "TIME 列往返应无损");
        assertEquals(0, loadedVerified.getLng().compareTo(new BigDecimal("118.5891234")),
                "DECIMAL(10,7) 坐标往返应无损");
    }

    @Test
    @DisplayName("门票 null 与 0 语义不同：null=未知，0=免费")
    void ticketPriceNullVersusZero() {
        Trip trip = new Trip();
        trip.setUserId(999_998L);
        trip.setTitle("票价语义");
        trip.setStatus(0);
        tripMapper.insert(trip);

        TripItem unknown = new TripItem();
        unknown.setTripId(trip.getId());
        unknown.setDayIndex(1);
        unknown.setSeq(0);
        unknown.setItemType(TripItem.TYPE_SCENIC);
        unknown.setPoiName("票价未知的景点");
        unknown.setTicketPrice(null);
        tripItemMapper.insert(unknown);

        TripItem free = new TripItem();
        free.setTripId(trip.getId());
        free.setDayIndex(1);
        free.setSeq(1);
        free.setItemType(TripItem.TYPE_SCENIC);
        free.setPoiName("免费景点");
        free.setTicketPrice(BigDecimal.ZERO);
        tripItemMapper.insert(free);

        assertNull(tripItemMapper.selectById(unknown.getId()).getTicketPrice(),
                "未知票价必须是 NULL");
        assertEquals(0, tripItemMapper.selectById(free.getId()).getTicketPrice()
                .compareTo(BigDecimal.ZERO), "免费景点的票价是真实的 0");
    }

    @Test
    @DisplayName("trip 的逻辑删除走 deleted 标记，普通查询自动过滤")
    void tripLogicDelete() {
        Trip trip = new Trip();
        trip.setUserId(999_998L);
        trip.setTitle("待删除的行程");
        trip.setStatus(0);
        tripMapper.insert(trip);
        Long id = trip.getId();

        assertNotNull(tripMapper.selectById(id), "删除前应能查到");
        assertEquals(1, tripMapper.deleteById(id), "逻辑删除应返回 1 行受影响");
        assertNull(tripMapper.selectById(id), "逻辑删除后普通查询应查不到（@TableLogic 生效）");

        // 注意：这里只能证明「查不到」。要证明「行还在、只是 deleted=1」得看物理表 ——
        // 本测试是 @Transactional 会自动回滚，所以那一步在测试外用 mysql 直接查。
    }

    private void insertItem(Long tripId, int dayIndex, int seq, String type, String name, String address, String ticket) {
        TripItem item = new TripItem();
        item.setTripId(tripId);
        item.setDayIndex(dayIndex);
        item.setSeq(seq);
        item.setItemType(type);
        item.setPoiName(name);
        item.setAddress(address);
        item.setTicketPrice(new BigDecimal(ticket));
        item.setReason("顺路且符合偏好");
        assertEquals(1, tripItemMapper.insert(item));
    }
}
