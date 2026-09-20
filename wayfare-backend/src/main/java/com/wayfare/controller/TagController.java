package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.entity.Tag;
import com.wayfare.security.UserContext;
import com.wayfare.service.TagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PostMapping
    public Result<Tag> create(@RequestBody Map<String, String> params) {
        checkAdmin();
        String name = params.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "标签名称不能为空");
        }
        return Result.success(tagService.create(name));
    }

    @PutMapping("/{id}")
    public Result<Tag> update(@PathVariable Long id, @RequestBody Map<String, String> params) {
        checkAdmin();
        String name = params.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "标签名称不能为空");
        }
        return Result.success(tagService.update(id, name));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        checkAdmin();
        tagService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Tag> getById(@PathVariable Long id) {
        return Result.success(tagService.getById(id));
    }

    @GetMapping("/page")
    public Result<IPage<Tag>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Result.success(tagService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/hot")
    public Result<List<Tag>> hotTags(@RequestParam(defaultValue = "20") Integer limit) {
        return Result.success(tagService.hotTags(limit));
    }

    @PostMapping("/batch")
    public Result<List<Tag>> listByIds(@RequestBody List<Long> ids) {
        return Result.success(tagService.listByIds(ids));
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
