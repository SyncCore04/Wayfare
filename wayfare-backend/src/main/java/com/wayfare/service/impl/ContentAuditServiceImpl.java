package com.wayfare.service.impl;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.service.ContentAuditService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内容审核服务实现
 * 基于关键词列表的简单过滤，支持动态增删
 */
@Service
public class ContentAuditServiceImpl implements ContentAuditService {

    private static final Logger log = LoggerFactory.getLogger(ContentAuditServiceImpl.class);

    /**
     * 默认敏感词列表（可通过配置文件覆盖）
     */
    @Value("${content.audit.sensitive-words:色情,暴力,赌博,毒品,诈骗,反动,政治敏感,广告推广,联系方式}")
    private String sensitiveWordsConfig;

    /**
     * 运行时敏感词列表（线程安全）
     */
    private final CopyOnWriteArrayList<String> sensitiveWords = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        if (StringUtils.hasText(sensitiveWordsConfig)) {
            String[] words = sensitiveWordsConfig.split(",");
            for (String word : words) {
                String trimmed = word.trim();
                if (StringUtils.hasText(trimmed) && !sensitiveWords.contains(trimmed)) {
                    sensitiveWords.add(trimmed);
                }
            }
        }
        log.info("内容审核服务初始化完成，加载敏感词 {} 个", sensitiveWords.size());
    }

    @Override
    public boolean containsViolation(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lowerText = text.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerText.contains(word.toLowerCase())) {
                log.warn("内容审核命中敏感词: '{}', 内容片段: '{}'", word,
                        text.length() > 50 ? text.substring(0, 50) + "..." : text);
                return true;
            }
        }
        return false;
    }

    @Override
    public void audit(String text, String fieldName) {
        if (containsViolation(text)) {
            throw new BusinessException(ResultCode.CONTENT_VIOLATION.getCode(),
                    fieldName + "包含违规内容，请修改后重试");
        }
    }

    @Override
    public List<String> getSensitiveWords() {
        return new ArrayList<>(sensitiveWords);
    }

    @Override
    public void addSensitiveWord(String word) {
        if (StringUtils.hasText(word) && !sensitiveWords.contains(word.trim())) {
            sensitiveWords.add(word.trim());
            log.info("新增敏感词: {}", word);
        }
    }

    @Override
    public void removeSensitiveWord(String word) {
        if (StringUtils.hasText(word)) {
            sensitiveWords.remove(word.trim());
            log.info("移除敏感词: {}", word);
        }
    }
}
