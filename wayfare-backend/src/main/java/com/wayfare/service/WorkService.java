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
