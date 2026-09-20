package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.entity.Tag;
import com.wayfare.entity.User;
import com.wayfare.entity.Work;
import com.wayfare.entity.WorkTag;
import com.wayfare.mapper.TagMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.mapper.WorkMapper;
import com.wayfare.mapper.WorkTagMapper;
import com.wayfare.service.BrowseHistoryService;
import com.wayfare.service.RecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐服务实现
 * 基于用户浏览历史和作品标签的简易协同过滤推荐
 *
 * 推荐策略：
 * 1. 从浏览历史提取用户偏好标签（Top 5）
 * 2. 查询带有同类标签的作品，统计标签匹配度
 * 3. 排除已浏览作品，按匹配度排序
 * 4. 热门作品按 点赞数*3 + 浏览量 加权排序
 * 5. 首页瀑布流混合 70%个性化 + 30%热门
 */
@Service
public class RecommendServiceImpl implements RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendServiceImpl.class);

    private static final int PREFERRED_TAG_COUNT = 5;
    private static final double PERSONAL_RATIO = 0.7;

    private final WorkMapper workMapper;
    private final WorkTagMapper workTagMapper;
    private final UserMapper userMapper;
    private final TagMapper tagMapper;
    private final BrowseHistoryService browseHistoryService;

    public RecommendServiceImpl(WorkMapper workMapper, WorkTagMapper workTagMapper,
                                UserMapper userMapper, TagMapper tagMapper,
                                BrowseHistoryService browseHistoryService) {
        this.workMapper = workMapper;
        this.workTagMapper = workTagMapper;
        this.userMapper = userMapper;
        this.tagMapper = tagMapper;
        this.browseHistoryService = browseHistoryService;
    }

    @Override
    public IPage<Work> getPersonalRecommend(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 1. 获取用户偏好标签
        List<Long> preferredTags = browseHistoryService.getUserPreferredTags(userId, PREFERRED_TAG_COUNT);
        if (preferredTags.isEmpty()) {
            log.debug("用户{}无偏好标签，返回热门作品", userId);
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 2. 查询这些标签关联的所有作品
        LambdaQueryWrapper<WorkTag> tagWrapper = new LambdaQueryWrapper<>();
        tagWrapper.in(WorkTag::getTagId, preferredTags);
        List<WorkTag> workTags = workTagMapper.selectList(tagWrapper);

        if (workTags.isEmpty()) {
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 3. 统计每个作品匹配的标签数量
        Map<Long, Integer> workMatchCount = new HashMap<>();
        for (WorkTag wt : workTags) {
            workMatchCount.merge(wt.getWorkId(), 1, Integer::sum);
        }

        // 4. 排除已浏览的作品
        List<Long> browsedWorks = browseHistoryService.getBrowseHistory(userId, 200);
        Set<Long> browsedSet = new HashSet<>(browsedWorks);

        // 5. 按匹配标签数量降序排序
        List<Map.Entry<Long, Integer>> sortedWorks = workMatchCount.entrySet().stream()
                .filter(e -> !browsedSet.contains(e.getKey()))
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toList());

        if (sortedWorks.isEmpty()) {
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 6. 手动分页
        int total = sortedWorks.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<Long> pageWorkIds = (fromIndex < total)
                ? sortedWorks.subList(fromIndex, toIndex).stream().map(Map.Entry::getKey).collect(Collectors.toList())
                : Collections.emptyList();

        // 7. 查询作品详情（只查已发布的）
        List<Work> works = Collections.emptyList();
        if (!pageWorkIds.isEmpty()) {
            LambdaQueryWrapper<Work> workWrapper = new LambdaQueryWrapper<>();
            workWrapper.in(Work::getId, pageWorkIds)
                    .eq(Work::getStatus, 1);
            works = workMapper.selectList(workWrapper);
            // 保持按匹配度排序的顺序
            Map<Long, Work> workMap = works.stream().collect(Collectors.toMap(Work::getId, w -> w));
            works = pageWorkIds.stream().map(workMap::get).filter(Objects::nonNull).collect(Collectors.toList());
        }

        Page<Work> page = new Page<>(pageNum, pageSize);
        page.setRecords(works);
        page.setTotal(total);
        page.setPages((int) Math.ceil((double) total / pageSize));

        // 填充作者、标签、图片URL
        fillWorkInfo(works);

        log.debug("用户{}个性化推荐: 偏好标签{}个, 候选作品{}个, 返回{}个",
                userId, preferredTags.size(), total, works.size());
        return page;
    }

    @Override
    public IPage<Work> getHotWorks(Integer pageNum, Integer pageSize, String sortBy) {
        Page<Work> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Work> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);

        // 排序方式
        if ("like".equalsIgnoreCase(sortBy)) {
            wrapper.orderByDesc("like_count");
            wrapper.orderByDesc("published_at");
        } else if ("view".equalsIgnoreCase(sortBy)) {
            wrapper.orderByDesc("view_count");
            wrapper.orderByDesc("published_at");
        } else {
            // 综合热度: 点赞数*3 + 浏览量，再按发布时间倒序
            wrapper.last("ORDER BY (like_count * 3 + view_count) DESC, published_at DESC");
        }

        IPage<Work> result = workMapper.selectPage(page, wrapper);
        fillWorkInfo(result.getRecords());
        return result;
    }

    @Override
    public IPage<Work> getFeed(Long userId, Integer pageNum, Integer pageSize) {
        // 未登录用户直接返回热门
        if (userId == null) {
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 检查用户是否有浏览历史
        long browseCount = browseHistoryService.getBrowseCount(userId);
        if (browseCount == 0) {
            log.debug("用户{}无浏览历史，Feed返回热门作品", userId);
            return getHotWorks(pageNum, pageSize, "hot");
        }

        // 计算个性化推荐和热门作品的数量
        int personalCount = (int) Math.ceil(pageSize * PERSONAL_RATIO);
        int hotCount = pageSize - personalCount;

        // 获取个性化推荐（取足够多以便去重）
        IPage<Work> personalPage = getPersonalRecommend(userId, 1, pageSize * 2);
        List<Work> personalWorks = new ArrayList<>(personalPage.getRecords());

        // 获取热门作品
        IPage<Work> hotPage = getHotWorks(1, pageSize * 2, "hot");
        List<Work> hotWorks = new ArrayList<>(hotPage.getRecords());

        // 去重合并：先个性化，后热门补充
        Set<Long> seenIds = new HashSet<>();
        List<Work> merged = new ArrayList<>();

        // 加入个性化推荐
        int personalAdded = 0;
        for (Work w : personalWorks) {
            if (personalAdded >= personalCount) break;
            if (!seenIds.contains(w.getId())) {
                merged.add(w);
                seenIds.add(w.getId());
                personalAdded++;
            }
        }

        // 用热门作品补充
        for (Work w : hotWorks) {
            if (merged.size() >= pageSize) break;
            if (!seenIds.contains(w.getId())) {
                merged.add(w);
                seenIds.add(w.getId());
            }
        }

        // 如果还不够，继续从热门中取
        if (merged.size() < pageSize && hotWorks.size() > pageSize * 2) {
            for (Work w : hotWorks) {
                if (merged.size() >= pageSize) break;
                if (!seenIds.contains(w.getId())) {
                    merged.add(w);
                    seenIds.add(w.getId());
                }
            }
        }

        Page<Work> resultPage = new Page<>(pageNum, pageSize);
        resultPage.setRecords(merged);
        resultPage.setTotal(Math.max(personalPage.getTotal(), hotPage.getTotal()));
        resultPage.setPages((int) Math.ceil((double) resultPage.getTotal() / pageSize));

        log.debug("用户{}Feed: 个性化{}个, 热门补充{}个, 共{}个",
                userId, personalAdded, merged.size() - personalAdded, merged.size());
        return resultPage;
    }

    @Override
    public Map<Long, Integer> getUserPreferenceAnalysis(Long userId) {
        if (userId == null) return Collections.emptyMap();

        // 获取用户偏好标签及频次
        Map<Long, Integer> tagScores = browseHistoryService.getUserPreferredTagsWithScore(userId, 20);
        if (tagScores.isEmpty()) return Collections.emptyMap();

        // 查询每个标签关联的作品数量
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : tagScores.entrySet()) {
            Long tagId = entry.getKey();
            LambdaQueryWrapper<WorkTag> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(WorkTag::getTagId, tagId);
            Integer workCount = Math.toIntExact(workTagMapper.selectCount(wrapper));
            result.put(tagId, workCount);
        }
        return result;
    }

    @Override
    public List<Work> getSimilarWorks(Long workId, int limit) {
        if (workId == null) return Collections.emptyList();

        // 1. 查询当前作品的标签
        LambdaQueryWrapper<WorkTag> currentWrapper = new LambdaQueryWrapper<>();
        currentWrapper.eq(WorkTag::getWorkId, workId);
        List<WorkTag> currentTags = workTagMapper.selectList(currentWrapper);
        if (currentTags.isEmpty()) return Collections.emptyList();

        List<Long> tagIds = currentTags.stream().map(WorkTag::getTagId).collect(Collectors.toList());

        // 2. 查询带有相同标签的其他作品
        LambdaQueryWrapper<WorkTag> similarWrapper = new LambdaQueryWrapper<>();
        similarWrapper.in(WorkTag::getTagId, tagIds)
                .ne(WorkTag::getWorkId, workId);
        List<WorkTag> similarTags = workTagMapper.selectList(similarWrapper);

        if (similarTags.isEmpty()) return Collections.emptyList();

        // 3. 统计每个作品匹配的标签数量
        Map<Long, Integer> matchCount = new HashMap<>();
        for (WorkTag wt : similarTags) {
            matchCount.merge(wt.getWorkId(), 1, Integer::sum);
        }

        // 4. 按匹配度排序，取Top N
        List<Long> similarWorkIds = matchCount.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (similarWorkIds.isEmpty()) return Collections.emptyList();

        // 5. 查询作品详情
        LambdaQueryWrapper<Work> workWrapper = new LambdaQueryWrapper<>();
        workWrapper.in(Work::getId, similarWorkIds)
                .eq(Work::getStatus, 1);
        List<Work> works = workMapper.selectList(workWrapper);

        // 保持匹配度排序
        Map<Long, Work> workMap = works.stream().collect(Collectors.toMap(Work::getId, w -> w));
        return similarWorkIds.stream().map(workMap::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 批量填充作品的作者信息、标签信息、图片URL
     */
    private void fillWorkInfo(List<Work> works) {
        if (works == null || works.isEmpty()) return;

        // 1. 批量查询作者信息
        List<Long> authorIds = works.stream()
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
        List<Long> workIds = works.stream().map(Work::getId).collect(Collectors.toList());
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

        // 3. 填充每个作品
        Map<Long, User> finalAuthorMap = authorMap;
        Map<Long, Tag> finalTagMap = tagMap;
        works.forEach(work -> {
            work.setAuthor(finalAuthorMap.get(work.getUserId()));
            List<WorkTag> wts = workTagMap.get(work.getId());
            if (wts != null && !wts.isEmpty()) {
                List<Tag> tagList = wts.stream()
                        .map(wt -> finalTagMap.get(wt.getTagId()))
                        .filter(t -> t != null)
                        .collect(Collectors.toList());
                work.setTags(tagList);
            }
            if (work.getCoverUrl() != null) {
                work.setImageUrls(Collections.singletonList(work.getCoverUrl()));
            }
        });
    }
}
