package com.wayfare;

import com.wayfare.entity.User;
import com.wayfare.mapper.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容域「表 ↔ 实体」映射冒烟验证（P0-B 验收项 2）。
 *
 * <p>为什么需要它：P0-B 的验收要求「启动项目，MyBatis-Plus 能映射全部实体，无 SQL 报错」，
 * 但 P0-B 又不允许写 Controller / Service，所以没有接口可以触发 SQL。
 * 光启动应用只能证明 Bean 装配成功，证明不了实体字段与库里列名对得上 ——
 * 列名写错会在真正查询时才炸。
 *
 * <p>这里对 14 个 Mapper 各跑一次 {@code selectList(null)}：
 * MyBatis-Plus 会按实体生成**完整列名清单**的 SELECT，任何「实体有、库里没有」的字段
 * 都会直接抛 SQL 异常，从而把映射问题在 P0-B 阶段就暴露出来，而不是拖到 P0-C 联调。
 * 表当前都是空的，所以查询开销可忽略。
 *
 * <p>注意：这只能验证「实体 → 库表」方向；库里多出实体没声明的列不会报错（属允许情况）。
 */
@SpringBootTest
class SchemaMappingSmokeTest {

    @Autowired private UserMapper userMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private WorkMapper workMapper;
    @Autowired private WorkImageMapper workImageMapper;
    @Autowired private WorkTagMapper workTagMapper;
    @Autowired private CommentMapper commentMapper;
    @Autowired private LikeRecordMapper likeRecordMapper;
    @Autowired private FavoriteMapper favoriteMapper;
    @Autowired private FollowMapper followMapper;
    @Autowired private PrivateMessageMapper privateMessageMapper;
    @Autowired private UserThirdAccountMapper userThirdAccountMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private AdminOperationLogMapper adminOperationLogMapper;

    @Test
    @DisplayName("14 张表的实体列映射全部可用，无 SQL 报错")
    void allFourteenMappersMapCleanly() {
        assertDoesNotThrow(() -> userMapper.selectList(null), "sys_user");
        assertDoesNotThrow(() -> categoryMapper.selectList(null), "category");
        assertDoesNotThrow(() -> tagMapper.selectList(null), "tag");
        assertDoesNotThrow(() -> workMapper.selectList(null), "work");
        assertDoesNotThrow(() -> workImageMapper.selectList(null), "work_image");
        assertDoesNotThrow(() -> workTagMapper.selectList(null), "work_tag");
        assertDoesNotThrow(() -> commentMapper.selectList(null), "comment");
        assertDoesNotThrow(() -> likeRecordMapper.selectList(null), "like_record");
        assertDoesNotThrow(() -> favoriteMapper.selectList(null), "favorite");
        assertDoesNotThrow(() -> followMapper.selectList(null), "follow");
        assertDoesNotThrow(() -> privateMessageMapper.selectList(null), "private_message");
        assertDoesNotThrow(() -> userThirdAccountMapper.selectList(null), "user_third_account");
        assertDoesNotThrow(() -> reportMapper.selectList(null), "report");
        assertDoesNotThrow(() -> adminOperationLogMapper.selectList(null), "admin_operation_log");
    }

    @Test
    @DisplayName("攻略特有的 destination / trip_days 真实存在于 work 表")
    void workHasTravelSpecificColumns() {
        // selectList 会 SELECT 全部实体列；若 destination/trip_days 未建列，这里就会抛异常
        List<com.wayfare.entity.Work> works = workMapper.selectList(null);
        assertNotNull(works);
        // 顺手断言 getter 存在，防止字段只加了一半
        com.wayfare.entity.Work probe = new com.wayfare.entity.Work();
        probe.setDestination("泉州");
        probe.setTripDays(3);
        assertEquals("泉州", probe.getDestination());
        assertEquals(3, probe.getTripDays());
    }

    @Test
    @DisplayName("初始数据可用：admin 密码能通过 BCrypt 校验、分类是 8 个攻略分类")
    void seedDataIsUsable() {
        User admin = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "admin"));
        assertNotNull(admin, "初始管理员账号不存在");
        assertEquals("admin", admin.getRole());
        assertNotNull(admin.getPassword(), "管理员密码为空，登录会失败");

        // 用项目真实依赖校验哈希，而不是只检查非空 —— 这才是「密码可用」的证据
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("Admin123456", admin.getPassword()),
                "密码哈希与 Admin123456 不匹配");

        List<com.wayfare.entity.Category> categories = categoryMapper.selectList(null);
        assertEquals(8, categories.size(), "攻略分类应为 8 个");
        List<String> names = categories.stream().map(com.wayfare.entity.Category::getName).toList();
        assertTrue(names.containsAll(List.of(
                        "古建探访", "自然风光", "博物馆", "市井烟火",
                        "美食之旅", "亲子出行", "摄影旅拍", "城市漫步")),
                "分类种子数据不是攻略分类，实际为：" + names);
    }

    @Test
    @DisplayName("admin_operation_log 可真实写入（P0-B 要求不留空表）")
    void adminOperationLogIsWritable() {
        com.wayfare.entity.AdminOperationLog log = new com.wayfare.entity.AdminOperationLog();
        log.setAdminId(1L);
        log.setModule("category");
        log.setAction("audit");
        log.setTargetType("category");
        log.setTargetId(1L);
        log.setDetail("{\"smoke\":\"P0-B 写入验证\"}");
        log.setIp("127.0.0.1");

        int inserted = adminOperationLogMapper.insert(log);
        assertEquals(1, inserted);
        assertNotNull(log.getId(), "自增主键未回填");
        assertNotNull(log.getCreatedAt(), "created_at 自动填充未生效");

        // 清理掉验证数据，保持库干净
        assertEquals(1, adminOperationLogMapper.deleteById(log.getId()));
    }
}
