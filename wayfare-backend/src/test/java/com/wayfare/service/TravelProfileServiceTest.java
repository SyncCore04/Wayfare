package com.wayfare.service;

import com.wayfare.dto.TravelProfileDTO;
import com.wayfare.entity.UserTravelProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户画像服务集成测试（P2-A 验收 1）。
 *
 * <p><b>为什么要真连库而不是 mock Mapper</b>：这一块最容易出错的地方恰恰是
 * 「实体字段 ↔ 库列名」以及「null 能不能真的写进去」——
 * 这两件事 mock 掉 Mapper 之后全都验不到（mock 永远返回你让它返回的东西）。
 * 所以这里起真 Spring 上下文、连真库。
 *
 * <p><b>用 {@code @Transactional} 而不是手工清理</b>：测试方法结束时自动回滚，
 * 不留任何残留数据。测试用的是 999999 这种不可能与真实用户冲突的 userId，
 * 双保险。
 */
@SpringBootTest
@Transactional
class TravelProfileServiceTest {

    /** 测试专用 userId：远大于真实用户，避免污染 */
    private static final Long TEST_USER_ID = 999_999L;

    @Autowired
    private TravelProfileService travelProfileService;

    private static TravelProfileDTO fullDto() {
        TravelProfileDTO dto = new TravelProfileDTO();
        dto.setCuisines("晋菜,面食,家常菜");
        dto.setFlavors("偏咸,微辣");
        dto.setTaboos("香菜,花生,海鲜");
        dto.setTravelStyles("古建探访,摄影旅拍");
        dto.setPace(1);
        dto.setBudgetLevel(1);
        dto.setCompanions("情侣");
        dto.setWalkLimitKm(8);
        dto.setHotelPref("民宿");
        dto.setNotes("不喜欢人多的景区");
        return dto;
    }

    @Test
    @DisplayName("验收1：保存后再获取，字段完整往返")
    void saveThenGetRoundTrip() {
        travelProfileService.save(TEST_USER_ID, fullDto());

        UserTravelProfile loaded = travelProfileService.getByUserId(TEST_USER_ID);

        assertNotNull(loaded, "刚保存过，不该是空画像");
        assertEquals(TEST_USER_ID, loaded.getUserId());
        assertEquals("晋菜,面食,家常菜", loaded.getCuisines());
        assertEquals("偏咸,微辣", loaded.getFlavors());
        assertEquals("香菜,花生,海鲜", loaded.getTaboos());
        assertEquals("古建探访,摄影旅拍", loaded.getTravelStyles());
        assertEquals(1, loaded.getPace());
        assertEquals(1, loaded.getBudgetLevel());
        assertEquals("情侣", loaded.getCompanions());
        assertEquals(8, loaded.getWalkLimitKm());
        assertEquals("民宿", loaded.getHotelPref());
        assertEquals("不喜欢人多的景区", loaded.getNotes());
        assertEquals(1, loaded.getAllowAiUse());
        // created_at / updated_at 由 MetaObjectHandler 与库默认值填充
        assertNotNull(loaded.getCreatedAt(), "created_at 未被填充");
        assertNotNull(loaded.getUpdatedAt(), "updated_at 未被填充");
    }

    @Test
    @DisplayName("画像不存在时返回「空画像」而不是 null，也不抛 404")
    void missingProfileReturnsEmptyObject() {
        UserTravelProfile loaded = travelProfileService.getByUserId(TEST_USER_ID);

        assertNotNull(loaded, "接口要求不存在时返回空对象");
        assertEquals(TEST_USER_ID, loaded.getUserId());
        assertEquals(1, loaded.getAllowAiUse(), "空画像的隐私开关默认应为 1");
        assertNull(loaded.getId(), "空画像不该有主键");
        assertNull(loaded.getCuisines());
        assertNull(loaded.getPace());
    }

    @Test
    @DisplayName("二次保存是整体替换：清空的字段要真的变成 NULL（updateById 会静默跳过 null）")
    void secondSaveReplacesAndPersistsNulls() {
        travelProfileService.save(TEST_USER_ID, fullDto());
        Long firstId = travelProfileService.getByUserId(TEST_USER_ID).getId();

        // 用户把「步行上限」「住宿偏好」「节奏」都清掉了
        TravelProfileDTO cleared = new TravelProfileDTO();
        cleared.setCuisines("晋菜");
        travelProfileService.save(TEST_USER_ID, cleared);

        UserTravelProfile loaded = travelProfileService.getByUserId(TEST_USER_ID);
        assertEquals("晋菜", loaded.getCuisines());
        assertNull(loaded.getPace(), "被清空的 pace 必须是 NULL —— 这正是不能用 updateById 的原因");
        assertNull(loaded.getWalkLimitKm(), "被清空的 walk_limit_km 必须是 NULL");
        assertNull(loaded.getHotelPref(), "被清空的 hotel_pref 必须是 NULL");
        assertNull(loaded.getTaboos(), "请求里没带的字段按整体替换语义应被清空");
        assertNull(loaded.getNotes());

        // 主键会变（删了重插），这是本实现的已知代价；表无外部引用，可接受
        assertNotNull(loaded.getId());
        assertFalse(firstId.equals(loaded.getId()), "删了重插后主键应变化（本实现的有意取舍）");
    }

    @Test
    @DisplayName("同一用户只保留一行，重复保存不会撑出多条")
    void upsertKeepsSingleRow() {
        travelProfileService.save(TEST_USER_ID, fullDto());
        travelProfileService.save(TEST_USER_ID, fullDto());
        travelProfileService.save(TEST_USER_ID, fullDto());

        // getByUserId 用 selectOne，若真有重复行会抛 TooManyResultsException，走到这里即证明唯一
        assertNotNull(travelProfileService.getByUserId(TEST_USER_ID));
    }

    @Test
    @DisplayName("空串被归一化成 null，逗号列表被清理后落库")
    void blankNormalizedToNull() {
        TravelProfileDTO dto = new TravelProfileDTO();
        dto.setCuisines("   ");
        dto.setFlavors("晋菜, , 面食 ");
        dto.setHotelPref("");
        travelProfileService.save(TEST_USER_ID, dto);

        UserTravelProfile loaded = travelProfileService.getByUserId(TEST_USER_ID);
        assertNull(loaded.getCuisines(), "纯空白应归一化成 NULL");
        assertNull(loaded.getHotelPref(), "空串应归一化成 NULL");
        assertEquals("晋菜,面食", loaded.getFlavors(), "多余空白与空段应被清理");
    }

    @Test
    @DisplayName("隐私开关：不传默认允许，显式传 0 能被保存")
    void allowAiUseSwitch() {
        TravelProfileDTO defaulted = new TravelProfileDTO();
        defaulted.setCuisines("晋菜");
        travelProfileService.save(TEST_USER_ID, defaulted);
        assertEquals(1, travelProfileService.getByUserId(TEST_USER_ID).getAllowAiUse(),
                "不传 allowAiUse 应默认允许");

        TravelProfileDTO disabled = new TravelProfileDTO();
        disabled.setCuisines("晋菜");
        disabled.setAllowAiUse(0);
        travelProfileService.save(TEST_USER_ID, disabled);
        assertEquals(0, travelProfileService.getByUserId(TEST_USER_ID).getAllowAiUse(),
                "显式关闭必须能落库");
    }

    @Test
    @DisplayName("画像与渲染器串起来：保存后的画像能渲染出预期的文本块")
    void savedProfileRenders() {
        travelProfileService.save(TEST_USER_ID, fullDto());
        UserTravelProfile loaded = travelProfileService.getByUserId(TEST_USER_ID);

        String rendered = new com.wayfare.profile.ProfileRenderer().render(loaded, null);

        assertTrue(rendered.startsWith("【用户画像】"));
        assertTrue(rendered.contains("菜系偏好：晋菜、面食、家常菜"), "实际输出 = " + rendered);
        assertTrue(rendered.contains("单日步行上限：8 公里"), "实际输出 = " + rendered);
    }
}
