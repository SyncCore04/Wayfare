package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.dto.WorkDTO;
import com.wayfare.entity.Work;

/**
 * 作品服务接口
 */
public interface WorkService {

    /**
     * 创建作品
     */
    Work create(WorkDTO workDTO, Long userId);

    /**
     * 更新作品
     */
    Work update(Long id, WorkDTO workDTO, Long userId, boolean isAdmin);

    /**
     * 删除作品（逻辑删除）
     */
    void delete(Long id, Long userId, boolean isAdmin);

    /**
     * 根据ID查询作品
     */
    Work getById(Long id);

    /**
     * 取某条攻略关联的行程（P5-C · 详情页的「完整行程」区块用）。
     *
     * <p><b>可见性规则（手册 P5-C 第三节）</b>：行程默认仅本人可见，**发布为攻略时才随之公开**。
     * 所以这里只在 {@code work.status == 1}（已发布）时才返回行程 —— 未发布/待审核的攻略，
     * 即便有人猜到 workId 也拿不到行程内容。
     *
     * @return 关联行程（含 days + items，按 dayIndex/seq 正序）；无关联或未发布时返回 null
     *         —— 「纯图文攻略」是正常状态，不是错误，所以不抛 404
     */
    com.wayfare.entity.Trip getTripByWorkId(Long workId);

    /**
     * 分页查询作品列表
     *
     * @param destination 目的地精确匹配（攻略特有筛选维度，走 idx_destination）
     * @param tripDays    行程天数精确匹配（攻略特有筛选维度）
     * @param minTripDays 行程天数下限，用于首页「更多」档（即 ≥N 天）；与 tripDays 互斥使用
     */
    IPage<Work> page(Integer pageNum, Integer pageSize, Long userId, Long categoryId,
                     Integer status, String keyword, String destination, Integer tripDays,
                     Integer minTripDays);

    /**
     * 浏览量+1
     */
    void incrementViewCount(Long id);

    /**
     * 更新作品状态（管理员审核用）
     */
    void updateStatus(Long id, Integer status);
}
