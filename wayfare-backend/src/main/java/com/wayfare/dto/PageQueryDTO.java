package com.wayfare.dto;

/**
 * 分页查询基础DTO
 */
public class PageQueryDTO {

    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String orderBy;
    private String orderDir = "desc";

    public Integer getPageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, 100);
    }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public String getOrderBy() { return orderBy; }
    public void setOrderBy(String orderBy) { this.orderBy = orderBy; }
    public String getOrderDir() { return orderDir; }
    public void setOrderDir(String orderDir) { this.orderDir = orderDir; }
}
