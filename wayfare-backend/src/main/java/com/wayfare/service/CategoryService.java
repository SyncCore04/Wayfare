package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.dto.CategoryDTO;
import com.wayfare.entity.Category;

import java.util.List;

/**
 * 分类服务接口
 */
public interface CategoryService {

    /**
     * 二级分类树（仅启用状态的分类，公开接口用）
     */
    List<Category> tree();

    /**
     * 分页查询（后台管理用，含禁用分类）
     */
    IPage<Category> page(Integer pageNum, Integer pageSize, Integer status, String keyword);

    /**
     * 新建分类
     */
    Category create(CategoryDTO categoryDTO);

    /**
     * 更新分类
     */
    Category update(Long id, CategoryDTO categoryDTO);

    /**
     * 删除分类（有作品或子分类关联时拒绝）
     */
    void delete(Long id);

    /**
     * 根据ID查询分类
     */
    Category getById(Long id);
}
