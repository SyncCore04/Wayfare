package com.wayfare.profile;

import com.wayfare.entity.UserTravelProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像渲染器单元测试（P2-A 验收 2、3、4、5）。
 *
 * <p>为什么不用 {@code @SpringBootTest}：{@link ProfileRenderer} 是个纯函数式的渲染器，
 * 不依赖数据库与 Redis，直接 new 出来测最快也最稳定。
 * 渲染规则是这个类唯一的职责，用单测钉死比每次起服务手点可靠得多。
 */
class ProfileRendererTest {

    private final ProfileRenderer renderer = new ProfileRenderer();

    private static UserTravelProfile emptyProfile() {
        UserTravelProfile profile = new UserTravelProfile();
        profile.setUserId(1L);
        profile.setAllowAiUse(1);
        return profile;
    }

    // ==================== 验收 2 ====================

    @Test
    @DisplayName("验收2：全空画像 render 返回空字符串，hasAnyContent 返回 false")
    void emptyProfileRendersNothing() {
        UserTravelProfile profile = emptyProfile();

        assertFalse(renderer.hasAnyContent(profile), "全空画像不应被判为有内容");
        assertEquals("", renderer.render(profile, null), "全空画像应渲染成空字符串");
        assertEquals("", renderer.render(null, null), "null 画像同样渲染成空字符串");
        assertFalse(renderer.shouldInject(profile), "空画像不该注入");
    }

    // ==================== 验收 3 ====================

    @Test
    @DisplayName("验收3：只填忌口与节奏时，输出只有这两行，没有空字段行")
    void onlyTaboosAndPace() {
        UserTravelProfile profile = emptyProfile();
        profile.setTaboos("香菜,花生");
        profile.setPace(1);

        String out = renderer.render(profile, null);

        String expected = """
                【用户画像】
                忌口过敏（硬约束，任何推荐都不得包含）：香菜、花生
                节奏：慢（每天 2-3 个点）""";
        assertEquals(expected, out, "应只输出标题 + 忌口 + 节奏三行，空字段整行省略");
        assertFalse(out.contains("未填写"), "不得出现「未填写」这类占位");
        assertFalse(out.contains("菜系偏好"), "空字段的行必须整行消失");
    }

    @Test
    @DisplayName("验收3补充：忌口为空时，忌口行仍要输出并声明「无」")
    void emptyTaboosStillEmitsLine() {
        UserTravelProfile profile = emptyProfile();
        profile.setPace(3);

        String out = renderer.render(profile, null);

        String expected = """
                【用户画像】
                忌口过敏：无（这点可以更自由地推荐餐饮）
                节奏：紧凑（每天 4-5 个点）""";
        assertEquals(expected, out, "忌口是硬约束，为空也必须显式声明「无」");
    }

    // ==================== 验收 4 ====================

    @Test
    @DisplayName("验收4：overrides 忌口与画像合并（并集去重），overrides 节奏覆盖画像节奏")
    void overridesMergeAndOverride() {
        UserTravelProfile profile = emptyProfile();
        profile.setTaboos("香菜");
        profile.setPace(1); // 画像 = 慢

        ProfileOverrides overrides = new ProfileOverrides(List.of("花生"), 3); // 本次 = 紧凑

        String out = renderer.render(profile, overrides);

        assertTrue(out.contains("香菜、花生"), "忌口应合并为并集：实际输出 = " + out);
        assertTrue(out.contains("节奏：紧凑（每天 4-5 个点）"), "节奏应被本次条件覆盖：实际输出 = " + out);
        assertFalse(out.contains("节奏：慢"), "被覆盖的旧节奏不应出现");
    }

    @Test
    @DisplayName("验收4补充：overrides 忌口与画像重复时去重，不出现两次")
    void overridesTaboosDeduplicated() {
        UserTravelProfile profile = emptyProfile();
        profile.setTaboos("香菜,花生");

        String out = renderer.render(profile, new ProfileOverrides(List.of("花生", "海鲜"), null));

        assertTrue(out.contains("香菜、花生、海鲜"), "应去重后按首次出现顺序拼接：实际输出 = " + out);
        assertEquals(1, out.split("花生", -1).length - 1, "「花生」只应出现一次");
    }

    @Test
    @DisplayName("验收4补充：overrides 为空对象时不影响画像渲染")
    void emptyOverridesDoesNotChangeOutput() {
        UserTravelProfile profile = emptyProfile();
        profile.setCuisines("晋菜,面食");
        profile.setTaboos("香菜");

        String withoutOverrides = renderer.render(profile, null);
        String withEmptyOverrides = renderer.render(profile, new ProfileOverrides());

        assertEquals(withoutOverrides, withEmptyOverrides, "空 overrides 应与不传完全等价");
    }

    // ==================== 验收 5 ====================

    @Test
    @DisplayName("验收5：完整画像的 render 输出示例")
    void fullProfileRenderSample() {
        UserTravelProfile profile = emptyProfile();
        profile.setCuisines("晋菜,面食,家常菜");
        profile.setFlavors("偏咸,微辣");
        profile.setTaboos("香菜,花生,海鲜");
        profile.setTravelStyles("古建探访,摄影旅拍");
        profile.setPace(1);
        profile.setBudgetLevel(1);
        profile.setCompanions("情侣");
        profile.setWalkLimitKm(8);
        profile.setHotelPref("民宿");
        profile.setNotes("不喜欢人多的景区");

        String out = renderer.render(profile, null);

        String expected = """
                【用户画像】
                菜系偏好：晋菜、面食、家常菜
                口味偏好：偏咸、微辣
                忌口过敏（硬约束，任何推荐都不得包含）：香菜、花生、海鲜
                旅行风格：古建探访、摄影旅拍
                节奏：慢（每天 2-3 个点）
                预算倾向：经济
                常同行人：情侣
                单日步行上限：8 公里
                住宿偏好：民宿
                补充说明：不喜欢人多的景区""";
        assertEquals(expected, out);
    }

    // ==================== 健壮性 ====================

    @Test
    @DisplayName("隐私开关关闭时 shouldInject 为 false，但 hasAnyContent 仍为 true")
    void privacySwitchGatesInjection() {
        UserTravelProfile profile = emptyProfile();
        profile.setCuisines("晋菜");
        profile.setAllowAiUse(0);

        assertTrue(renderer.hasAnyContent(profile), "内容确实存在");
        assertFalse(renderer.shouldInject(profile), "但隐私开关关掉后不该注入（铁律3）");
    }

    @Test
    @DisplayName("非法枚举值按未填写处理：不渲染该行，也不算作内容")
    void invalidEnumValuesTreatedAsUnset() {
        UserTravelProfile profile = emptyProfile();
        profile.setPace(9);
        profile.setBudgetLevel(0);

        assertFalse(renderer.hasAnyContent(profile), "非法值不构成内容");
        assertEquals("", renderer.render(profile, null), "非法值不该被翻译成任何结论");
    }

    @Test
    @DisplayName("逗号列表容错：中英文逗号混用、多余空白与空段都会被清理")
    void csvNormalization() {
        UserTravelProfile profile = emptyProfile();
        profile.setCuisines("晋菜, , 面食，家常菜 ");

        String out = renderer.render(profile, null);

        assertTrue(out.contains("菜系偏好：晋菜、面食、家常菜"), "实际输出 = " + out);
    }

    @Test
    @DisplayName("输出不带结尾换行，且格式与 P3 的契约一致")
    void noTrailingNewline() {
        UserTravelProfile profile = emptyProfile();
        profile.setCuisines("晋菜");

        String out = renderer.render(profile, null);

        assertFalse(out.endsWith("\n"), "末尾不应有换行符");
        assertTrue(out.startsWith("【用户画像】\n"), "应以标题 + 换行开头");
    }
}
