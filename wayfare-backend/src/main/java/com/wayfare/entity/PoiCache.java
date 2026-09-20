package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * POI 本地缓存实体类
 * 对应数据库表: poi_cache
 *
 * <p><b>存在的意义</b>：地图服务熔断或断网时，行程不能直接瘫掉。
 * 有一份历史 POI 数据在手，至少能把「上次算过的点位」继续用起来，
 * 并把数据标记为 {@code CACHED} 让用户知道这不是刚查的。
 * 这是三级降级里的第二级。
 *
 * <p><b>坐标是 BD-09</b>（百度坐标系），与 {@code PoiDTO} 一致，不做转换。
 *
 * <p>注意：本表的建表语句目前放在 {@code db/schema-trip.sql} 的 P1-C 段落里
 * （P1-C 需要它才能验证缓存降级），P2-B 会把行程表族追加到同一个文件。
 */
@TableName("poi_cache")
public class PoiCache implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 百度 POI 唯一 ID */
    private String poiUid;

    private String name;
    private String address;

    /** 所属城市，检索缓存时按它过滤 */
    private String city;

    /** 经度（BD-09） */
    private Double lng;

    /** 纬度（BD-09） */
    private Double lat;

    private String tag;
    private String shopHours;

    /** 评分。百度常常不返回，取不到就存 null —— 不要用 0 冒充「未知」 */
    private Double rating;

    /** 票价。百度基本不返回，取不到存 null */
    private Double ticketPrice;

    /** 原始响应片段，便于排查字段缺失的原因 */
    private String rawJson;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPoiUid() { return poiUid; }
    public void setPoiUid(String poiUid) { this.poiUid = poiUid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getShopHours() { return shopHours; }
    public void setShopHours(String shopHours) { this.shopHours = shopHours; }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public Double getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(Double ticketPrice) { this.ticketPrice = ticketPrice; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
