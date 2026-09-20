package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 外部调用日志实体类
 * 对应数据库表: external_call_log
 *
 * <p><b>这张表是「出站调用到底发生了什么」的唯一底账</b>：成本统计（P4-C）、
 * 监控看板（P6-B）、简历指标（P7-B）都从它取数。
 *
 * <p><b>铁律：{@code requestSummary} 必须已经脱敏</b>（由 {@code MaskUtil.sanitize} 处理），
 * 库里不允许出现完整密钥。P7 会有测试专门验这一点。
 */
@TableName("external_call_log")
public class ExternalCallLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 触发调用的用户ID；系统任务（无请求上下文）时为 null */
    private Long userId;

    /** 关联行程ID；P3 起才会有值，P1 阶段一律 null */
    private Long tripId;

    /** 连接器类型 LLM | BAIDU_MAP */
    private String connector;

    /** 具体接口名，如 chat/completions、place/v2/search */
    private String apiName;

    /** 请求摘要（已脱敏 + 截断到 500 字符） */
    private String requestSummary;

    /** HTTP 状态码；没拿到响应（超时/连接失败）时为 null */
    private Integer httpStatus;

    private Integer durationMs;

    private Boolean success;

    /** 失败原因（已脱敏） */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public String getConnector() { return connector; }
    public void setConnector(String connector) { this.connector = connector; }
    public String getApiName() { return apiName; }
    public void setApiName(String apiName) { this.apiName = apiName; }
    public String getRequestSummary() { return requestSummary; }
    public void setRequestSummary(String requestSummary) { this.requestSummary = requestSummary; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
