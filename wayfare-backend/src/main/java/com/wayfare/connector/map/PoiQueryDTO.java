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

    /** 关键词，例如「开元寺」。<b>必填</b> —— 百度要求 query 必填，详见 {@link #tag} 的说明 */
    private String keyword;

    /**
     * 分类标签，例如「旅游景点」。
     *
     * <p>⚠️ <b>本项目当前不使用本字段</b>。2026-09-20 用真实 AK 逐组实测出三条教训：
     * <ol>
     *   <li><b>不能单独使用</b>：百度要求 query 必填，只发 tag 会直接返回
     *       {@code status=2 Parameter Invalid}（抛异常，整个阶段失败）；</li>
     *   <li><b>填错不报错，只会静默返回垃圾</b>：{@code tag=风景名胜} 时结果会变成
     *       「广州市/邵阳市/福州市」这类城市级噪声（实测 42 条全是噪声），
     *       而正确的 POI（方山国家森林公园、冷泉寺）一个都不剩。
     *       <b>这比报错危险得多 —— 它不会引起任何告警</b>；</li>
     *   <li><b>填合法取值也没有增益</b>：{@code query=景点&tag=旅游景点} 与 {@code query=景点}
     *       返回完全相同的 3 条结果。</li>
     * </ol>
     * 结论：一个「填错不报错、只会静默变垃圾」的参数，在没有可靠取值清单之前不该用。
     * 要用它必须先拿到百度的合法取值清单，并逐条实测确认结果确实变好。
     */
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
