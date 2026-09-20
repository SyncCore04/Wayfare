package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台操作日志实体类
 * 对应数据库表: admin_operation_log
 *
 * <p>P0-B 明确要求这张表「要真实写入，不要留空」—— 它是后台管理的审计底账，
 * 也是答辩时「有没有真做后台」的证据。因此实体与 Mapper 必须现在建好，
 * 由 P0-C 的业务代码在分类/标签/作品的管理动作里实际落记录。
 *
 * <p>{@code detail} 存 JSON 文本（对象变更前后快照），注意脱敏后再写入。
 */
@TableName("admin_operation_log")
public class AdminOperationLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminId;
    /** 操作模块 user / work / comment / category / tag / report */
    private String module;
    /** 操作动作 create / update / delete / audit / enable / disable */
    private String action;
    private String targetType;
    private Long targetId;
    /** 操作详情（JSON 文本） */
    private String detail;
    private String ip;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdminId() { return adminId; }
    public void setAdminId(Long adminId) { this.adminId = adminId; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
