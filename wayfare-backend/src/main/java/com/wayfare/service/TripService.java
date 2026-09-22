package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;

import java.util.List;

/**
 * 行程服务（P2-B 定签名，P3 填实现）。
 *
 * <p><b>本接口现在只有签名，没有实现。</b>P2-B 是纯数据层阶段，手册明确要求
 * 「不要写业务逻辑」—— 编排逻辑（分天、选点、校验、重排）全部属于 P3。
 *
 * <p>签名的设计意图（给 P3 的说明）：
 * <ul>
 *   <li>{@link #createDraft} —— P3 在「意图解析」之前先落一条草稿，
 *       这样后续任何一步失败，用户都能在「我的行程」里看到一条可续作的记录，
 *       而不是什么痕迹都没留下；</li>
 *   <li>{@link #saveFullTrip} —— 编排完成后的<b>一次性落库</b>，
 *       入参是已经组装好的聚合（{@link Trip} 里带 {@code dayPlans}，
 *       每个 {@link TripDay} 里带 {@code items}）。之所以不做成「一条条 insert」的
 *       细粒度接口，是因为整份行程是一个原子结果：半份行程对用户没有意义，
 *       必须整体成功或整体回滚；</li>
 *   <li>{@link #getDetail} —— 返回带 days + items 的完整聚合，按
 *       {@code dayIndex} 与 {@code seq} 正序；</li>
 *   <li>{@link #logicDelete} —— 走 {@code trip.deleted}，不是物理删除。</li>
 * </ul>
 *
 * <p><b>所有方法都必须校验 {@code userId} 归属</b>：行程是私有数据，
 * 「换个 id 就能看别人的行程」是这类接口最典型的越权漏洞。
 * 签名里带 {@code userId} 而不是只带 {@code tripId}，就是为了让越权检查无法被遗漏。
 */
public interface TripService {

    /**
     * 创建一条草稿行程。
     *
     * @param userId   所属用户
     * @param rawInput 用户原始输入（自然语言原文）
     * @return 落库后的草稿（含回填主键），status = 0
     */
    Trip createDraft(Long userId, String rawInput);

    /**
     * 保存完整行程：trip + 全部 trip_day + 全部 trip_item 一次性落库。
     *
     * <p>整体事务：任何一条写失败都应全部回滚。
     *
     * @param trip     行程主体（{@code id} 为空则新建，非空则覆盖既有行程的子表）
     * @param dayPlans 按天安排，每个 TripDay 内含 items
     * @return 落库后的行程
     */
    Trip saveFullTrip(Trip trip, List<TripDay> dayPlans);

    /**
     * 取行程详情（含 days 与 items，按 dayIndex、seq 正序）。
     *
     * @param tripId 行程ID
     * @param userId 当前用户ID，用于归属校验
     * @return 完整聚合；不存在或不属于该用户时返回 null
     */
    Trip getDetail(Long tripId, Long userId);

    /**
     * 我的行程分页（不含 days / items，列表页不需要）。
     */
    IPage<Trip> pageMy(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 逻辑删除行程（置 {@code trip.deleted}）。
     */
    void logicDelete(Long tripId, Long userId);

    /**
     * 重排某一天内的条目顺序（P5-B 的「上移 / 下移 / 拖拽」编辑用它）。
     *
     * @param tripId          行程ID
     * @param userId          当前用户ID，<b>用于归属校验</b> ——
     *                        签名里带上它，是为了让「换个 id 就改别人的行程顺序」不可能发生
     * @param dayIndex        第几天（从 1 起）
     * @param itemIdsInOrder  该天的条目ID，<b>按目标顺序排列</b>；
     *                        实现方按数组下标回写 {@code seq}（0 起）
     */
    void updateItemOrder(Long tripId, Long userId, Integer dayIndex, List<Long> itemIdsInOrder);

    /**
     * 写回攻略文案（P4-B）。
     *
     * <p>文案是「锦上添花」环节：它可能生成失败、可能被用户中断，而行程本身早已可用。
     * 所以它不走 {@link #saveFullTrip}（那是整份行程的原子替换，会把子表删了重建），
     * 而是一个只动 {@code trip.guide_text} 一列的单字段更新 ——
     * 失败或中断时什么都不写，行程完好无损。
     *
     * <p>同样必须校验 {@code userId} 归属。
     *
     * @param guideText 生成的文案；传 null 表示「无事可做」（本方法不支持把列清空）
     */
    void updateGuideText(Long tripId, Long userId, String guideText);
}
