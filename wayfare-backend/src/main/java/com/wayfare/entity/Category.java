package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品分类实体类
 * 对应数据库表: category
 *
 * <p>旧摄影平台的前端把 8 个分类硬编码在 WorkPublish.vue 里，数据库其实有 category 表却
 * 没有实体与 Mapper，等于「有表没代码」。本类补上这个缺口，分类一律从
 * {@code /categories/tree} 拉取，前端不再写死。
 *
 * <p>表结构支持两级分类（{@code parent_id} 为 0 表示一级分类），
 * 当前初始数据只有一级，但接口按树形设计，后续加子分类不用改表。
 */
@TableName("category")
public class Category implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** 父分类ID，0 表示一级分类 */
    private Long parentId;
    private String icon;
    /** 排序值，数字越小越靠前 */
    private Integer sort;
    /** 状态 0禁用 1启用 */
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // 非数据库字段：构建分类树时挂子节点，不参与 SQL
    @TableField(exist = false)
    private List<Category> children;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Category> getChildren() { return children; }
    public void setChildren(List<Category> children) { this.children = children; }
}
