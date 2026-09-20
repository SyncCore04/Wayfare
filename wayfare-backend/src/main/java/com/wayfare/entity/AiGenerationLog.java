package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 生成日志实体（P2-C）
 * 对应数据库表: ai_generation_log
 *
 * <p><b>本表是项目的成本与质量证据链</b>：P4 的成本控制、P6 的监控看板、
 * P7 的量化指标（简历上那三条亮点）全部从这里取数。
 *
 * <p><b>与 {@code external_call_log} 的分工（容易混，重点）</b>：
 * <ul>
 *   <li>{@code external_call_log} —— <b>传输层</b>视角，每次 HTTP 尝试一条
 *       （GET 重试会留 2 条），由出站治理层自动写；</li>
 *   <li>本表 —— <b>业务阶段</b>视角，一个管线阶段一条，记录这一步的语义任务成没成、
 *       花了多少钱。一次阶段调用可能对应多条 {@code external_call_log}（因为重试），
 *       两者<b>不是一一对应</b>，所以不能合并成一张表。</li>
 * </ul>
 */
@TableName("ai_generation_log")
public class AiGenerationLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 触发用户ID，系统任务为 null */
    private Long userId;

    /** 关联行程ID，解析阶段可能还没有行程 */
    private Long tripId;

    /** 管线阶段 PARSE/CANDIDATE/PREORDER/COMPOSE/VALIDATE/ROUTE/COPY */
    private String stage;

    /** 厂商 glm/deepseek/mock/baidu */
    private String provider;

    private String model;

    /** 输入 token；拿不到为 null，不要写 0 */
    private Integer promptTokens;

    /** 输出 token；拿不到为 null */
    private Integer completionTokens;

    /** 总 token；拿不到为 null */
    private Integer totalTokens;

    private Integer durationMs;

    /** 是否成功 0否 1是 */
    private Integer success;

    /** 统一错误码，取值见 {@code com.wayfare.trip.AiErrorCode}；成功时为 null */
    private String errorCode;

    /** 失败详情（已脱敏） */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Integer getSuccess() { return success; }
    public void setSuccess(Integer success) { this.success = success; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
