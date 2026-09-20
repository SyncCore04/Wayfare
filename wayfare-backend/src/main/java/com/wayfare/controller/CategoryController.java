package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.CategoryDTO;
import com.wayfare.entity.Category;
import com.wayfare.security.UserContext;
import com.wayfare.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类接口。
 *
 * <p>权限边界：
 * <ul>
 *   <li>{@code GET /categories/tree} 在 WebMvcConfig 白名单里，未登录可访问（前台筛选要用）；</li>
 *   <li>其余全部需要登录，写操作再额外要求管理员角色 —— 并<b>没有被放进白名单</b>，
 *       这是手册的硬要求：管理接口不能在鉴权名单里开后门。</li>
 * </ul>
 */
@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 二级分类树（公开）
     */
    @GetMapping("/tree")
    public Result<List<Category>> tree() {
        return Result.success(categoryService.tree());
    }

    /**
     * 分类分页（后台管理，含禁用）
     */
    @GetMapping("/page")
    public Result<IPage<Category>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        checkAdmin();
        return Result.success(categoryService.page(pageNum, pageSize, status, keyword));
    }

    @GetMapping("/{id}")
    public Result<Category> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    @PostMapping
    public Result<Category> create(@Valid @RequestBody CategoryDTO categoryDTO) {
        checkAdmin();
        return Result.success(categoryService.create(categoryDTO));
    }

    @PutMapping("/{id}")
    public Result<Category> update(@PathVariable Long id, @Valid @RequestBody CategoryDTO categoryDTO) {
        checkAdmin();
        return Result.success(categoryService.update(id, categoryDTO));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        checkAdmin();
        categoryService.delete(id);
        return Result.success();
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
