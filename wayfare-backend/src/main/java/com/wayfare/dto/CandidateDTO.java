package com.wayfare.dto;

import com.wayfare.entity.TripItem;

import java.io.Serializable;

/**
 * 候选点位（P3-B · 管线 Step 2 的产物）。
 *
 * <p><b>它是「防幻觉第一道闸门」的载体</b>：P3-D 的行程编排只允许从这个池子里选点，
 * P3-E 的 CLOSURE 规则再校验一次「你选的每个点都在池子里」。
 * 换句话说，<b>模型编出来的景点在物理上进不了行程</b> —— 不是靠提示词求它别编，
 * 而是靠数据结构让它编的东西没有落脚点。
 *
 * <p><b>数据诚信的两个标记必须成对出现</b>（与 {@link TripItem} 同一套常量，不要写字面量）：
 * <ul>
 *   <li>{@link #dataSource}：这条数据是<b>谁给的</b>（BAIDU 实测 / LLM 生成 / USER 手填）</li>
 *   <li>{@link #verifyStatus}：这条数据<b>有多可信</b>（VERIFIED / CACHED / ESTIMATED）</li>
 * </ul>
 * 地图关闭时走 LLM 生成路径，此时 {@code dataSource=LLM} + {@code verifyStatus=ESTIMATED}，
 * 且 {@link #lng} / {@link #lat} <b>必须留 null</b> —— 绝不允许模型给一个经纬度。
 *
 * <p>{@link #rating} 与 {@link #ticketPrice} 的 <b>null 表示「未知」，不是 0</b>：
 * 百度检索接口经常不返回评分、基本不返回票价，用 0 冒充会让前端显示「0 分」这种假精度。
 * 这与 {@code PoiDTO} 的原则一致。
 */
public class CandidateDTO implements Serializable {

    /** 百度 POI 唯一 id。LLM 生成的候选没有它（留 null），CLOSURE 校验时会退回按名称匹配 */
    private String poiUid;

    /** 点位名称 */
    private String name;

    /** 类型，取值见 {@link TripItem#TYPE_SCENIC} / {@link TripItem#TYPE_FOOD} 等 */
    private String itemType;

    /** 所属区域，如「寿阳县城东」。地图关闭时由模型给（它只能说个大概），地图开启时来自地址 */
    private String area;

    /** 详细地址。地图关闭时为 null —— 模型不许编门牌号 */
    private String address;

    /** 经度（BD-09）。<b>地图不可用时为 null，绝不留假值</b> */
    private Double lng;

    /** 纬度（BD-09）。地图不可用时为 null */
    private Double lat;

    /** 建议停留时长（分钟）。来自映射字典的 stayMinutes，或按 itemType 的默认值 */
    private Integer stayMinutes;

    /** 一句话亮点。地图开启时可为空（百度不给），地图关闭时由模型写 */
    private String highlight;

    /** 数据来源，见 {@link TripItem#SOURCE_BAIDU} / {@link TripItem#SOURCE_LLM} */
    private String dataSource;

    /** 可信度，见 {@link TripItem#VERIFY_VERIFIED} / {@link TripItem#VERIFY_CACHED} / {@link TripItem#VERIFY_ESTIMATED} */
    private String verifyStatus;

    /** 地图返回的分类标签，用于排查「为什么这个点被搜出来了」 */
    private String tag;

    /** 评分。<b>null = 未知</b>，不是 0 分 */
    private Double rating;

    /** 门票价。<b>null = 未知</b>，0 才是免费 */
    private Double ticketPrice;

    /** 这条候选是由哪个偏好/菜系检索出来的（如「古建筑」「面食」），排查漏检时用 */
    private String fromPreference;

    // ==================== getter / setter ====================

    public String getPoiUid() { return poiUid; }
    public void setPoiUid(String poiUid) { this.poiUid = poiUid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Integer getStayMinutes() { return stayMinutes; }
    public void setStayMinutes(Integer stayMinutes) { this.stayMinutes = stayMinutes; }

    public String getHighlight() { return highlight; }
    public void setHighlight(String highlight) { this.highlight = highlight; }

    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }

    public String getVerifyStatus() { return verifyStatus; }
    public void setVerifyStatus(String verifyStatus) { this.verifyStatus = verifyStatus; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }

    public Double getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(Double ticketPrice) { this.ticketPrice = ticketPrice; }

    public String getFromPreference() { return fromPreference; }
    public void setFromPreference(String fromPreference) { this.fromPreference = fromPreference; }

    /** 是否拿到了坐标。P3-C 的空间预排据此决定能不能参与排序（无坐标的排到最后） */
    public boolean hasLocation() {
        return lng != null && lat != null;
    }

    /** 是否为餐饮点。P3-D 的「每天至少 1 个 FOOD」与 P3-E 的忌口校验都靠它 */
    public boolean isFood() {
        return TripItem.TYPE_FOOD.equals(itemType);
    }
}
