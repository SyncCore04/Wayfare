package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.CategoryDTO;
import com.wayfare.entity.Category;
import com.wayfare.entity.Work;
import com.wayfare.mapper.CategoryMapper;
import com.wayfare.mapper.WorkMapper;
import com.wayfare.service.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分类服务实现。
 *
 * <p>这套东西存在的意义：旧版本前端把 8 个分类硬编码在页面里，数据库里的 category 表
 * 有数据却没有任何代码读它，等于「改分类要发版」。P0-C 起分类由本服务管理，
 * 前端改为拉 {@code /categories/tree}。
 *
 * <p>两级树的约定：{@code parent_id = 0} 为一级分类，其余挂到对应父节点下。
 * 初始数据只有一级，但接口按树形返回，后续加子分类不用改表也不用改前端。
 * <b>当前只拼两级</b>：三级及更深不会出现在树里（也无此需求）。
 *
 * <p>{@code tree()} 只返回启用状态的分类；后台分页接口返回全部（含禁用），
 * 否则管理员改不了自己禁用的分类。
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    /** 一级分类的 parent_id 约定值 */
    private static final long ROOT_PARENT_ID = 0L;

    private final CategoryMapper categoryMapper;
    private final WorkMapper workMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper, WorkMapper workMapper) {
        this.categoryMapper = categoryMapper;
        this.workMapper = workMapper;
    }

    @Override
    public List<Category> tree() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        // 树是给前台浏览用的，禁用分类不该出现
        wrapper.eq(Category::getStatus, 1);
        wrapper.orderByAsc(Category::getSort).orderByAsc(Category::getId);
        List<Category> all = categoryMapper.selectList(wrapper);

        // groupingBy 默认用 ArrayList，会保持上面的查询顺序，所以子节点顺序不用再排
        Map<Long, List<Category>> byParent = all.stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() == null ? ROOT_PARENT_ID : c.getParentId()));

        List<Category> roots = byParent.getOrDefault(ROOT_PARENT_ID, new ArrayList<>());
        for (Category root : roots) {
            List<Category> children = byParent.get(root.getId());
            if (children != null && !children.isEmpty()) {
                root.setChildren(children);
            }
        }
        return roots;
    }

    @Override
    public IPage<Category> page(Integer pageNum, Integer pageSize, Integer status, String keyword) {
        Page<Category> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(Category::getStatus, status);
        if (StringUtils.hasText(keyword)) wrapper.like(Category::getName, keyword);
        wrapper.orderByAsc(Category::getSort).orderByAsc(Category::getId);
        return categoryMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Category create(CategoryDTO categoryDTO) {
        String name = categoryDTO.getName() == null ? null : categoryDTO.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "分类名称不能为空");
        }
        Long parentId = categoryDTO.getParentId() != null ? categoryDTO.getParentId() : ROOT_PARENT_ID;

        // 表上有 uk_name_parent 唯一键，这里先查一次是为了给出可读的错误提示而不是 500
        Category exist = getByNameAndParent(name, parentId);
        if (exist != null) {
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXIST);
        }

        Category category = new Category();
        category.setName(name);
        category.setParentId(parentId);
        category.setIcon(categoryDTO.getIcon() != null ? categoryDTO.getIcon() : "");
        category.setSort(categoryDTO.getSort() != null ? categoryDTO.getSort() : 0);
        category.setStatus(categoryDTO.getStatus() != null ? categoryDTO.getStatus() : 1);
        categoryMapper.insert(category);

        log.info("分类创建成功: categoryId={}, name={}, parentId={}", category.getId(), name, parentId);
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Category update(Long id, CategoryDTO categoryDTO) {
        Category exist = getById(id);

        Category update = new Category();
        update.setId(id);

        if (categoryDTO.getName() != null) {
            String name = categoryDTO.getName().trim();
            if (!StringUtils.hasText(name)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "分类名称不能为空");
            }
            Long parentId = categoryDTO.getParentId() != null ? categoryDTO.getParentId() : exist.getParentId();
            Category sameName = getByNameAndParent(name, parentId);
            if (sameName != null && !sameName.getId().equals(id)) {
                throw new BusinessException(ResultCode.CATEGORY_NAME_EXIST);
            }
            update.setName(name);
        }

        if (categoryDTO.getParentId() != null) {
            // 不允许把自己挂到自己下面，否则树会自环、前端渲染直接死循环
            if (categoryDTO.getParentId().equals(id)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不能把分类挂到自己下面");
            }
            update.setParentId(categoryDTO.getParentId());
        }
        if (categoryDTO.getIcon() != null) update.setIcon(categoryDTO.getIcon());
        if (categoryDTO.getSort() != null) update.setSort(categoryDTO.getSort());
        if (categoryDTO.getStatus() != null) update.setStatus(categoryDTO.getStatus());

        categoryMapper.updateById(update);
        log.info("分类更新成功: categoryId={}", id);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Category category = getById(id);

        // 无物理外键，关联完整性只能在这里守。先查子分类：删掉父级会让子分类变成孤儿
        LambdaQueryWrapper<Category> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.eq(Category::getParentId, id);
        Long childCount = categoryMapper.selectCount(childWrapper);
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ResultCode.CATEGORY_HAS_CHILDREN);
        }

        // 再查作品：仍有作品引用时拒绝删除，避免作品挂到一个不存在的分类上
        LambdaQueryWrapper<Work> workWrapper = new LambdaQueryWrapper<>();
        workWrapper.eq(Work::getCategoryId, id);
        Long workCount = workMapper.selectCount(workWrapper);
        if (workCount != null && workCount > 0) {
            throw new BusinessException(ResultCode.CATEGORY_HAS_WORK);
        }

        categoryMapper.deleteById(id);
        log.info("分类删除成功: categoryId={}, name={}", id, category.getName());
    }

    @Override
    public Category getById(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private Category getByNameAndParent(String name, Long parentId) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, name);
        wrapper.eq(Category::getParentId, parentId != null ? parentId : ROOT_PARENT_ID);
        return categoryMapper.selectOne(wrapper);
    }
}
