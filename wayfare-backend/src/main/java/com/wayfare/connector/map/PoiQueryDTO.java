package com.wayfare.connector.map;

import java.io.Serializable;

/**
 * POI 检索条件。
 *
 * <p>对应百度 {@code /place/v2/search} 的参数。注意百度的 {@code page_num} 是
 * <b>从 0 开始</b>的（不是 1），且 {@code page_size} 上限 20 —— 这两点都很容易踩，
 * 所以在本类里统一成「从 1 开始 + 上限 20」的语义，转换在 BaiduMapProvider 里做。
 */
public class PoiQueryDTO implements Serializable {

    /** 城市/行政区名，例如「泉州」。百度必填项之一 */
    private String city;

    /** 关键词，例如「开元寺」。与 tag 二选一 */
    private String keyword;

    /** 分类标签，例如「旅游景点」。与 keyword 二选一 */
    private String tag;

    /** 页码，从 1 开始（对外语义） */
    private Integer pageNum = 1;

    /** 每页条数，上限 20（百度限制） */
    private Integer pageSize = 10;

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
}
