package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Tag;

import java.util.List;

/**
 * 标签服务接口
 */
public interface TagService {

    /**
     * 创建标签
     */
    Tag create(String name);

    /**
     * 更新标签
     */
    Tag update(Long id, String name);

    /**
     * 删除标签
     */
    void delete(Long id);

    /**
     * 根据ID查询标签
     */
    Tag getById(Long id);

    /**
     * 根据名称查询标签
     */
    Tag getByName(String name);

    /**
     * 分页查询标签列表
     */
    IPage<Tag> page(Integer pageNum, Integer pageSize, String keyword);

    /**
     * 热门标签列表（按使用次数排序）
     */
    List<Tag> hotTags(Integer limit);

    /**
     * 根据ID列表批量查询标签
     */
    List<Tag> listByIds(List<Long> ids);
}
