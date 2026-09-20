package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.WorkDTO;
import com.wayfare.entity.Work;
import com.wayfare.entity.WorkImage;
import com.wayfare.entity.WorkTag;
import com.wayfare.entity.Tag;
import com.wayfare.entity.User;
import com.wayfare.mapper.WorkImageMapper;
import com.wayfare.mapper.WorkMapper;
import com.wayfare.mapper.WorkTagMapper;
import com.wayfare.mapper.TagMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.service.ContentAuditService;
import com.wayfare.service.WorkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkServiceImpl implements WorkService {

    private static final Logger log = LoggerFactory.getLogger(WorkServiceImpl.class);

    private final WorkMapper workMapper;
    private final WorkImageMapper workImageMapper;
    private final WorkTagMapper workTagMapper;
    private final TagMapper tagMapper;
    private final UserMapper userMapper;
    private final ContentAuditService contentAuditService;

    public WorkServiceImpl(WorkMapper workMapper, WorkImageMapper workImageMapper,
                           WorkTagMapper workTagMapper, TagMapper tagMapper,
                           UserMapper userMapper, ContentAuditService contentAuditService) {
        this.workMapper = workMapper;
        this.workImageMapper = workImageMapper;
        this.workTagMapper = workTagMapper;
        this.tagMapper = tagMapper;
        this.userMapper = userMapper;
        this.contentAuditService = contentAuditService;
    }

    /**
     * 创建作品（攻略）。
     *
     * <p><b>多图必须真落库</b>：旧实现把 imageUrls 当成「封面这一张」的包装，
     * work_image 表建了却从来不写入，等于多图功能是假的。这里的顺序刻意设计为
     * 「先 insert work 拿到自增 id → 再用该 id 批量 insert work_image」，
     * 因为 work_image.work_id 依赖主键，反过来做不到。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Work create(WorkDTO workDTO, Long userId) {
        // 内容审核：命中敏感词直接抛业务异常，不产生任何入库
        contentAuditService.audit(workDTO.getTitle(), "作品标题");
        if (workDTO.getDescription() != null) {
            contentAuditService.audit(workDTO.getDescription(), "作品描述");
        }

        List<String> images = normalizeImages(workDTO);
        if (images.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "至少需要一张图片（imageUrls 或 coverUrl）");
        }

        Work work = new Work();
        work.setUserId(userId);
        work.setTitle(workDTO.getTitle());
        work.setDescription(workDTO.getDescription());
        work.setCategoryId(workDTO.getCategoryId() != null ? workDTO.getCategoryId() : 0L);
        // 封面永远取第一张，保证列表页/瀑布流有一张确定的图
        work.setCoverUrl(images.get(0));
        work.setCoverWidth(workDTO.getCoverWidth() != null ? workDTO.getCoverWidth() : 0);
        work.setCoverHeight(workDTO.getCoverHeight() != null ? workDTO.getCoverHeight() : 0);
        work.setDestination(workDTO.getDestination() != null ? workDTO.getDestination().trim() : "");
        work.setTripDays(workDTO.getTripDays() != null ? workDTO.getTripDays() : 1);
        work.setStatus(workDTO.getStatus() != null ? workDTO.getStatus() : 1);
        work.setIsWatermarked(1);
        work.setViewCount(0);
        work.setLikeCount(0);
        work.setCollectCount(0);
        work.setCommentCount(0);
        if (work.getStatus() == 1) {
            work.setPublishedAt(LocalDateTime.now());
        }
        // 第一步：插入主表，拿到自增 id
        workMapper.insert(work);
        // 第二步：用 id 批量插入图片明细（sort 从 0 递增，0 即封面）
        saveWorkImages(work.getId(), images, workDTO.getCoverWidth(), workDTO.getCoverHeight());
        saveWorkTags(work.getId(), workDTO.getTagIds());

        log.info("作品创建成功: workId={}, userId={}, title={}, 图片数={}",
                work.getId(), userId, work.getTitle(), images.size());
        return getById(work.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Work update(Long id, WorkDTO workDTO, Long userId, boolean isAdmin) {
        Work work = getById(id);
        if (!work.getUserId().equals(userId) && !isAdmin) {
            throw new BusinessException(ResultCode.WORK_NO_PERMISSION);
        }

        if (workDTO.getTitle() != null) {
            contentAuditService.audit(workDTO.getTitle(), "作品标题");
        }
        if (workDTO.getDescription() != null) {
            contentAuditService.audit(workDTO.getDescription(), "作品描述");
        }

        Work update = new Work();
        update.setId(id);
        if (workDTO.getTitle() != null) update.setTitle(workDTO.getTitle());
        if (workDTO.getDescription() != null) update.setDescription(workDTO.getDescription());
        if (workDTO.getCategoryId() != null) update.setCategoryId(workDTO.getCategoryId());
        if (workDTO.getDestination() != null) update.setDestination(workDTO.getDestination().trim());
        if (workDTO.getTripDays() != null) update.setTripDays(workDTO.getTripDays());
        if (workDTO.getCoverWidth() != null) update.setCoverWidth(workDTO.getCoverWidth());
        if (workDTO.getCoverHeight() != null) update.setCoverHeight(workDTO.getCoverHeight());
        if (workDTO.getStatus() != null) {
            update.setStatus(workDTO.getStatus());
            if (workDTO.getStatus() == 1 && work.getPublishedAt() == null) {
                update.setPublishedAt(LocalDateTime.now());
            }
        }

        // 图片列表非空才替换；为空/null 表示「本次不动图片」，交给 coverUrl 单独更新兜底
        List<String> images = normalizeImages(workDTO);
        if (!images.isEmpty()) {
            LambdaQueryWrapper<WorkImage> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(WorkImage::getWorkId, id);
            workImageMapper.delete(deleteWrapper);
            saveWorkImages(id, images, workDTO.getCoverWidth(), workDTO.getCoverHeight());
            // 封面必须跟着新的第一张走，否则会出现「封面还是旧图、图集已是新图」的错位
            update.setCoverUrl(images.get(0));
        } else if (workDTO.getCoverUrl() != null) {
            update.setCoverUrl(workDTO.getCoverUrl());
        }
        workMapper.updateById(update);

        if (workDTO.getTagIds() != null) {
            LambdaQueryWrapper<WorkTag> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(WorkTag::getWorkId, id);
            workTagMapper.delete(deleteWrapper);
            saveWorkTags(id, workDTO.getTagIds());
        }

        log.info("作品更新成功: workId={}", id);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId, boolean isAdmin) {
        Work work = getById(id);
        if (!work.getUserId().equals(userId) && !isAdmin) {
            throw new BusinessException(ResultCode.WORK_NO_PERMISSION);
        }
        workMapper.deleteById(id);
        LambdaQueryWrapper<WorkTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkTag::getWorkId, id);
        workTagMapper.delete(wrapper);
        // work 是逻辑删除（@TableLogic），work_image 是物理表，一并清掉避免残留脏数据
        LambdaQueryWrapper<WorkImage> imageWrapper = new LambdaQueryWrapper<>();
        imageWrapper.eq(WorkImage::getWorkId, id);
        workImageMapper.delete(imageWrapper);
        log.info("作品删除成功: workId={}, userId={}", id, userId);
    }

    @Override
    public Work getById(Long id) {
        Work work = workMapper.selectById(id);
        if (work == null) {
            throw new BusinessException(ResultCode.WORK_NOT_FOUND);
        }
        // 填充作者信息
        if (work.getUserId() != null) {
            User author = userMapper.selectById(work.getUserId());
            if (author != null) {
                author.setPassword(null); // 不返回密码
                work.setAuthor(author);
            }
        }
        // 填充标签信息
        LambdaQueryWrapper<WorkTag> wtWrapper = new LambdaQueryWrapper<>();
        wtWrapper.eq(WorkTag::getWorkId, id);
        List<WorkTag> workTags = workTagMapper.selectList(wtWrapper);
        if (workTags != null && !workTags.isEmpty()) {
            List<Long> tagIds = workTags.stream().map(WorkTag::getTagId).collect(Collectors.toList());
            List<Tag> tags = tagMapper.selectBatchIds(tagIds);
            work.setTags(tags);
        }
        // 图片列表：从 work_image 按 sort 正序取真实数据
        work.setImageUrls(loadImageUrls(id, work.getCoverUrl()));
        return work;
    }

    @Override
    public IPage<Work> page(Integer pageNum, Integer pageSize, Long userId, Long categoryId,
                            Integer status, String keyword, String destination, Integer tripDays,
                            Integer minTripDays) {
        Page<Work> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Work> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) wrapper.eq(Work::getUserId, userId);
        if (categoryId != null) wrapper.eq(Work::getCategoryId, categoryId);
        // 攻略特有筛选：精确匹配，才能吃到 idx_destination / idx_trip_days
        if (StringUtils.hasText(destination)) wrapper.eq(Work::getDestination, destination.trim());
        if (tripDays != null) {
            wrapper.eq(Work::getTripDays, tripDays);
        } else if (minTripDays != null) {
            // 首页「更多」档 = ≥N 天。只在没有精确天数时生效，避免两个条件互相打架
            wrapper.ge(Work::getTripDays, minTripDays);
        }
        if (status != null) {
            wrapper.eq(Work::getStatus, status);
        } else {
            wrapper.eq(Work::getStatus, 1);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Work::getTitle, keyword)
                    .or().like(Work::getDescription, keyword));
        }
        wrapper.orderByDesc(Work::getPublishedAt);
        IPage<Work> result = workMapper.selectPage(page, wrapper);

        // 批量填充作者、标签、图片：全部一次 IN 查询搞定，禁止在循环里查库
        List<Work> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            // 1. 批量查询作者信息
            List<Long> authorIds = records.stream()
                    .map(Work::getUserId)
                    .filter(id -> id != null)
                    .distinct()
                    .collect(Collectors.toList());
            Map<Long, User> authorMap = Collections.emptyMap();
            if (!authorIds.isEmpty()) {
                List<User> authors = userMapper.selectBatchIds(authorIds);
                authors.forEach(a -> a.setPassword(null));
                authorMap = authors.stream().collect(Collectors.toMap(User::getId, a -> a));
            }

            // 2. 批量查询标签信息
            List<Long> workIds = records.stream().map(Work::getId).collect(Collectors.toList());
            LambdaQueryWrapper<WorkTag> wtWrapper = new LambdaQueryWrapper<>();
            wtWrapper.in(WorkTag::getWorkId, workIds);
            List<WorkTag> workTags = workTagMapper.selectList(wtWrapper);
            Map<Long, List<WorkTag>> workTagMap = workTags.stream()
                    .collect(Collectors.groupingBy(WorkTag::getWorkId));

            List<Long> tagIds = workTags.stream()
                    .map(WorkTag::getTagId)
                    .distinct()
                    .collect(Collectors.toList());
            Map<Long, Tag> tagMap = Collections.emptyMap();
            if (!tagIds.isEmpty()) {
                List<Tag> tags = tagMapper.selectBatchIds(tagIds);
                tagMap = tags.stream().collect(Collectors.toMap(Tag::getId, t -> t));
            }

            // 3. 批量查询图片：一次 IN 查询捞出整页作品的图片，再按 workId 分组
            LambdaQueryWrapper<WorkImage> wiWrapper = new LambdaQueryWrapper<>();
            wiWrapper.in(WorkImage::getWorkId, workIds).orderByAsc(WorkImage::getSort);
            List<WorkImage> allImages = workImageMapper.selectList(wiWrapper);
            // groupingBy 用的 list 保持上面 sort 升序的遍历顺序
            Map<Long, List<String>> imageMap = allImages.stream()
                    .collect(Collectors.groupingBy(WorkImage::getWorkId,
                            Collectors.mapping(WorkImage::getImageUrl, Collectors.toList())));

            // 4. 填充每个作品的作者、标签、图片列表
            Map<Long, User> finalAuthorMap = authorMap;
            Map<Long, Tag> finalTagMap = tagMap;
            records.forEach(work -> {
                work.setAuthor(finalAuthorMap.get(work.getUserId()));
                List<WorkTag> wts = workTagMap.get(work.getId());
                if (wts != null && !wts.isEmpty()) {
                    List<Tag> tagList = wts.stream()
                            .map(wt -> finalTagMap.get(wt.getTagId()))
                            .filter(t -> t != null)
                            .collect(Collectors.toList());
                    work.setTags(tagList);
                }
                List<String> urls = imageMap.get(work.getId());
                work.setImageUrls(urls != null && !urls.isEmpty()
                        ? urls
                        : coverFallback(work.getCoverUrl()));
            });
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void incrementViewCount(Long id) {
        Work work = workMapper.selectById(id);
        if (work != null) {
            Work update = new Work();
            update.setId(id);
            update.setViewCount(work.getViewCount() + 1);
            workMapper.updateById(update);
        }
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Work update = new Work();
        update.setId(id);
        update.setStatus(status);
        workMapper.updateById(update);
        log.info("作品{}状态已更新为{}", id, status);
    }

    /**
     * 归一化图片列表：以 imageUrls 为准，为空时退回单图 coverUrl。
     * 顺带过滤空白项，避免前端传 ["", "  "] 这种脏数据入库。
     */
    private List<String> normalizeImages(WorkDTO workDTO) {
        List<String> images = new ArrayList<>();
        if (workDTO.getImageUrls() != null) {
            for (String url : workDTO.getImageUrls()) {
                if (StringUtils.hasText(url)) {
                    images.add(url.trim());
                }
            }
        }
        if (images.isEmpty() && StringUtils.hasText(workDTO.getCoverUrl())) {
            images.add(workDTO.getCoverUrl().trim());
        }
        return images;
    }

    /**
     * 批量写入图片明细。
     *
     * <p>已知取舍：{@code work_image.width/height} 目前统一写调用方给的封面尺寸（缺省 0），
     * 因为手册规定的请求体只有 {@code imageUrls: string[]}，没有携带每张图各自的宽高。
     * 上传接口（/files/upload）是返回 width/height 的，若要做精确瀑布流，
     * 后续把请求体改成 {@code [{url,width,height}]} 即可，本方法跟着改一处。
     */
    private void saveWorkImages(Long workId, List<String> imageUrls, Integer width, Integer height) {
        if (imageUrls == null || imageUrls.isEmpty()) return;
        List<WorkImage> imageList = new ArrayList<>(imageUrls.size());
        for (int i = 0; i < imageUrls.size(); i++) {
            WorkImage image = new WorkImage();
            image.setWorkId(workId);
            image.setImageUrl(imageUrls.get(i));
            image.setWidth(width != null ? width : 0);
            image.setHeight(height != null ? height : 0);
            image.setSort(i); // 0 就是封面
            imageList.add(image);
        }
        workImageMapper.insertBatch(imageList);
    }

    /**
     * 取某作品的图片 URL 列表（按 sort 正序）。
     * work_image 没有记录时退回封面单图 —— 兼容本修复之前创建的历史数据。
     */
    private List<String> loadImageUrls(Long workId, String coverUrl) {
        LambdaQueryWrapper<WorkImage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkImage::getWorkId, workId).orderByAsc(WorkImage::getSort);
        List<WorkImage> images = workImageMapper.selectList(wrapper);
        if (images != null && !images.isEmpty()) {
            return images.stream().map(WorkImage::getImageUrl).collect(Collectors.toList());
        }
        return coverFallback(coverUrl);
    }

    private List<String> coverFallback(String coverUrl) {
        return StringUtils.hasText(coverUrl) ? Collections.singletonList(coverUrl) : Collections.emptyList();
    }

    private void saveWorkTags(Long workId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        for (Long tagId : tagIds) {
            if (tagId == null) continue;
            WorkTag workTag = new WorkTag();
            workTag.setWorkId(workId);
            workTag.setTagId(tagId);
            workTagMapper.insert(workTag);
        }
    }
}
