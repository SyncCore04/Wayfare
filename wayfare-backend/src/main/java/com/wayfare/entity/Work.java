package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品实体类
 * 对应数据库表: work
 */
@TableName("work")
public class Work implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long categoryId;
    private String title;
    private String description;
    private String coverUrl;
    private Integer coverWidth;
    private Integer coverHeight;
    /** 目的地：攻略特有的筛选维度（如「泉州」「京都」），旧摄影平台没有这个字段 */
    private String destination;
    /** 行程天数：攻略特有的筛选维度 */
    private Integer tripDays;
    private Integer viewCount;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Integer isWatermarked;
    private Integer status;
    private LocalDateTime publishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    // 非数据库字段：用于详情页展示
    @TableField(exist = false)
    private com.wayfare.entity.User author;

    @TableField(exist = false)
    private List<Tag> tags;

    @TableField(exist = false)
    private List<String> imageUrls;

    /**
     * 这条攻略是否由 AI 行程生成而来（P5-C）。
     *
     * <p><b>不是表字段</b>：work 表没有 trip_id，关联关系存在反方向（{@code trip.work_id}）。
     * 由 Service 查询后批量回填，用于卡片上的「AI 生成」角标 ——
     * 让用户能区分人工原创与 AI 辅助生成，这是内容诚信，也是本项目的差异化卖点。
     *
     * <p>为 null 表示「未知」（老数据或未回填），前端按「不显示角标」处理。
     */
    @TableField(exist = false)
    private Boolean aiGenerated;

    public Boolean getAiGenerated() { return aiGenerated; }
    public void setAiGenerated(Boolean aiGenerated) { this.aiGenerated = aiGenerated; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public Integer getCoverWidth() { return coverWidth; }
    public void setCoverWidth(Integer coverWidth) { this.coverWidth = coverWidth; }
    public Integer getCoverHeight() { return coverHeight; }
    public void setCoverHeight(Integer coverHeight) { this.coverHeight = coverHeight; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public Integer getTripDays() { return tripDays; }
    public void setTripDays(Integer tripDays) { this.tripDays = tripDays; }
    public Integer getViewCount() { return viewCount; }
    public void setViewCount(Integer viewCount) { this.viewCount = viewCount; }
    public Integer getLikeCount() { return likeCount; }
    public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
    public Integer getCollectCount() { return collectCount; }
    public void setCollectCount(Integer collectCount) { this.collectCount = collectCount; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Integer getIsWatermarked() { return isWatermarked; }
    public void setIsWatermarked(Integer isWatermarked) { this.isWatermarked = isWatermarked; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public com.wayfare.entity.User getAuthor() { return author; }
    public void setAuthor(com.wayfare.entity.User author) { this.author = author; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
}
