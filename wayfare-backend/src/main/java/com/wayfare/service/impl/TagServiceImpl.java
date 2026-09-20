package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.entity.Tag;
import com.wayfare.entity.WorkTag;
import com.wayfare.mapper.TagMapper;
import com.wayfare.mapper.WorkTagMapper;
import com.wayfare.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class TagServiceImpl implements TagService {

    private static final Logger log = LoggerFactory.getLogger(TagServiceImpl.class);

    private final TagMapper tagMapper;
    private final WorkTagMapper workTagMapper;

    public TagServiceImpl(TagMapper tagMapper, WorkTagMapper workTagMapper) {
        this.tagMapper = tagMapper;
        this.workTagMapper = workTagMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tag create(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "标签名称不能为空");
        }
        Tag exist = getByName(name.trim());
        if (exist != null) {
            throw new BusinessException(ResultCode.TAG_NAME_EXIST);
        }
        Tag tag = new Tag();
        tag.setName(name.trim());
        tag.setUseCount(0);
        tagMapper.insert(tag);
        log.info("标签创建成功: tagId={}, name={}", tag.getId(), tag.getName());
        return tag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tag update(Long id, String name) {
        Tag tag = getById(id);
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "标签名称不能为空");
        }
        Tag exist = getByName(name.trim());
        if (exist != null && !exist.getId().equals(id)) {
            throw new BusinessException(ResultCode.TAG_NAME_EXIST);
        }
        Tag update = new Tag();
        update.setId(id);
        update.setName(name.trim());
        tagMapper.updateById(update);
        log.info("标签更新成功: tagId={}, name={}", id, name);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Tag tag = getById(id);
        LambdaQueryWrapper<WorkTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkTag::getTagId, id);
        workTagMapper.delete(wrapper);
        tagMapper.deleteById(id);
        log.info("标签删除成功: tagId={}, name={}", id, tag.getName());
    }

    @Override
    public Tag getById(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ResultCode.TAG_NOT_FOUND);
        }
        return tag;
    }

    @Override
    public Tag getByName(String name) {
        if (!StringUtils.hasText(name)) return null;
        LambdaQueryWrapper<Tag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Tag::getName, name.trim());
        return tagMapper.selectOne(wrapper);
    }

    @Override
    public IPage<Tag> page(Integer pageNum, Integer pageSize, String keyword) {
        Page<Tag> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Tag> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Tag::getName, keyword);
        }
        wrapper.orderByDesc(Tag::getUseCount);
        return tagMapper.selectPage(page, wrapper);
    }

    @Override
    public List<Tag> hotTags(Integer limit) {
        Page<Tag> page = new Page<>(1, limit != null ? limit : 20);
        LambdaQueryWrapper<Tag> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Tag::getUseCount);
        return tagMapper.selectPage(page, wrapper).getRecords();
    }

    @Override
    public List<Tag> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return tagMapper.selectBatchIds(ids);
    }
}
