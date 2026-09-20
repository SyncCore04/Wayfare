package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运行时配置实体类（L2 开关层）
 * 对应数据库表: sys_config
 *
 * <p>三层配置里的中间那层：
 * <ul>
 *   <li><b>L1 启动期配置</b> = application.yml（{@code LlmProperties} / {@code MapProperties}），
 *       放连接地址、模型名、API Key 这类启动就必需的东西；</li>
 *   <li><b>L2 运行期开关</b> = 本表 + Redis 缓存 —— 后台改一下就该生效，
 *       不能为了切个厂商去重启服务；</li>
 *   <li><b>L3 降级</b> = 熔断器 + 分级 Provider（P1-D 实现）。</li>
 * </ul>
 * <b>读取优先级：L2 覆盖 L1；L2 里没有的键，回落到 L1。</b>
 *
 * <p>值统一按字符串存（{@code config_value}），靠 {@code valueType} 决定怎么解释 ——
 * 这样加一种新配置不需要改表结构。
 */
@TableName("sys_config")
public class SysConfig implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，全局唯一，形如 llm.active-provider */
    private String configKey;

    /** 配置值，统一字符串存储 */
    private String configValue;

    /** 值类型 STRING | INT | BOOL | JSON */
    private String valueType;

    /** 分组 llm | map | trip，后台按组展示 */
    private String groupName;

    private String description;

    /** 最后修改人ID，0 表示仍是系统初始值 */
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
