package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报实体类
 * 对应数据库表: report
 *
 * <p>按 P0-B 要求「预留」：本版只建表与实体，不写举报接口逻辑。
 * 表结构已支持举报作品 / 评论 / 用户三类对象（{@code target_type}），
 * 以及待处理→已处理/已驳回的流转（{@code status} + {@code handlerId} + {@code handleRemark}）。
 */
@TableName("report")
public class Report implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    /** 举报对象类型 1作品 2评论 3用户 */
    private Integer targetType;
    private Long targetId;
    private String reason;
    /** 处理状态 0待处理 1已处理 2已驳回 */
    private Integer status;
    /** 处理管理员ID，0 表示未处理 */
    private Long handlerId;
    private String handleRemark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }
    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getHandlerId() { return handlerId; }
    public void setHandlerId(Long handlerId) { this.handlerId = handlerId; }
    public String getHandleRemark() { return handleRemark; }
    public void setHandleRemark(String handleRemark) { this.handleRemark = handleRemark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
