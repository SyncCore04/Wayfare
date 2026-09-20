package com.wayfare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分类创建/更新请求DTO
 */
public class CategoryDTO {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称长度不能超过50位")
    private String name;

    /** 父分类ID，0 或不传表示一级分类 */
    private Long parentId;

    @Size(max = 255, message = "图标地址长度不能超过255位")
    private String icon;

    /** 排序值，数字越小越靠前 */
    private Integer sort;

    /** 状态 0禁用 1启用 */
    private Integer status;

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
}
