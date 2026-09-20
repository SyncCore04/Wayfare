package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作品图片明细实体类
 * 对应数据库表: work_image
 *
 * <p>旧代码把「多图发布」做成了假的：{@code WorkServiceImpl} 把 imageUrls 写死为
 * {@code Collections.singletonList(coverUrl)}，work_image 表建了却没有实体和 Mapper。
 * 本类补上这个缺口，之后 P0-C 要改成「先 insert work 拿 id，再按 sort 递增批量 insert 本表」，
 * 详情页从本表按 {@code sort} 正序读取真实图片列表。
 *
 * <p>{@code sort} 为 0 的那张即封面（与 work.cover_url 保持一致）。
 */
@TableName("work_image")
public class WorkImage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workId;
    private String imageUrl;
    private Integer width;
    private Integer height;
    /** 在作品中的排序，0 为封面 */
    private Integer sort;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
