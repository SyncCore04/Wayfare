package com.wayfare.service;

import java.util.List;

/**
 * 内容审核服务接口
 * 基于关键词过滤的简单内容审核
 */
public interface ContentAuditService {

    /**
     * 检查文本是否包含违规关键词
     *
     * @param text 待检查文本
     * @return true=包含违规词, false=正常
     */
    boolean containsViolation(String text);

    /**
     * 审核文本内容，若包含违规词则抛出异常
     *
     * @param text 待审核文本
     * @param fieldName 字段名（用于错误提示）
     */
    void audit(String text, String fieldName);

    /**
     * 获取所有违规关键词
     */
    List<String> getSensitiveWords();

    /**
     * 动态添加敏感词
     */
    void addSensitiveWord(String word);

    /**
     * 动态移除敏感词
     */
    void removeSensitiveWord(String word);
}
