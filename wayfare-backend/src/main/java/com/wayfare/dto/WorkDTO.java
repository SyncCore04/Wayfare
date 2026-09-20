package com.wayfare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 作品（攻略）创建/更新请求DTO
 *
 * <p>关于封面：本版支持多图，{@code coverUrl} 不再是必填项 ——
 * 服务端会取 {@code imageUrls} 的第一张作为封面（并写进 work.cover_url）。
 * {@code coverUrl} 保留是为了兼容只传单图的老调用方；两者都不传时服务端会报参数错误。
 *
 * <p>{@code destination} 与 {@code tripDays} 是攻略特有的字段（摄影作品没有），
 * 列表页按这两个维度筛选。
 */
public class WorkDTO {

    @NotBlank(message = "作品标题不能为空")
    @Size(max = 100, message = "作品标题长度不能超过100位")
    private String title;

    @Size(max = 2000, message = "作品描述长度不能超过2000位")
    private String description;

    private Long categoryId;

    /**
     * 多图列表。非空时以它为准：第一张作封面，其余按 sort 顺序存 work_image 表。
     * 上限 20 张是工程保护（避免一次请求插入过多记录），规格里未规定，可按需调整。
     */
    @Size(max = 20, message = "最多只能上传20张图片")
    private List<String> imageUrls;

    /** 单图/兼容用法：imageUrls 为空时用它，等价于只传一张图 */
    private String coverUrl;

    private Integer coverWidth;

    private Integer coverHeight;

    /** 目的地（攻略特有筛选维度） */
    @Size(max = 50, message = "目的地长度不能超过50位")
    private String destination;

    /** 行程天数（攻略特有筛选维度） */
    private Integer tripDays;

    @Size(max = 10, message = "最多只能添加10个标签")
    private List<Long> tagIds;

    private Integer status;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
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
    public List<Long> getTagIds() { return tagIds; }
    public void setTagIds(List<Long> tagIds) { this.tagIds = tagIds; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
