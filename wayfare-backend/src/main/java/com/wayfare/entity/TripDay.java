package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 行程日实体（P2-B）
 * 对应数据库表: trip_day
 *
 * <p><b>{@code dayIndex} 从 1 起，而 {@link TripItem#getSeq()} 从 0 起 —— 这是有意的。</b>
 * 两者是两种语义：{@code dayIndex} 直接出现在界面上（「第 3 天」），人话从 1 数起；
 * {@code seq} 只在代码里排序，从 0 起是数组下标习惯。
 * 把它们统一成同一个起点，只会让渲染层到处出现 {@code +1} / {@code -1}，
 * 是 bug 温床 —— 所以保持现状，并在两处都写明。
 *
 * <p>本表按手册只有 {@code created_at}，没有 {@code updated_at}：
 * 当天主题几乎不会被单独编辑（改行程是整份重存）。
 */
@TableName("trip_day")
public class TripDay implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripId;

    /** 第几天，从 1 起 */
    private Integer dayIndex;

    /** 当天主题，如「古城与开元寺」 */
    private String title;

    private String summary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 非数据库字段：详情聚合用的当天条目，按 seq 正序 */
    @TableField(exist = false)
    private List<TripItem> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public Integer getDayIndex() { return dayIndex; }
    public void setDayIndex(Integer dayIndex) { this.dayIndex = dayIndex; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<TripItem> getItems() { return items; }
    public void setItems(List<TripItem> items) { this.items = items; }
}
