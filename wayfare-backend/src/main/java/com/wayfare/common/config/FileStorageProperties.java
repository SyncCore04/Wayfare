package com.wayfare.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置属性
 */
@Component
@ConfigurationProperties(prefix = "file.upload")
public class FileStorageProperties {

    /** 本地存储根目录（实际由 application.yml 的 file.upload.upload-dir 覆盖） */
    private String uploadDir = "D:/Code/Vibe coding test/Wayfare/wayfare-backend/uploads";

    /** 访问URL前缀 */
    private String accessPrefix = "/uploads";

    /** 允许的文件类型 */
    private String[] allowedTypes = {"image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"};

    /** 允许的文件扩展名 */
    private String[] allowedExtensions = {"jpg", "jpeg", "png", "gif", "webp", "bmp"};

    /** 单文件最大大小（字节），默认10MB */
    private Long maxFileSize = 10 * 1024 * 1024L;

    /** 水印文字 */
    private String watermarkText = "行走集 Wayfare";

    /** 水印字体大小 */
    private Integer watermarkFontSize = 36;

    /** 水印透明度（0-1） */
    private Float watermarkOpacity = 0.3f;

    /** 水印颜色RGB */
    private String watermarkColor = "255,255,255";

    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
    public String getAccessPrefix() { return accessPrefix; }
    public void setAccessPrefix(String accessPrefix) { this.accessPrefix = accessPrefix; }
    public String[] getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(String[] allowedTypes) { this.allowedTypes = allowedTypes; }
    public String[] getAllowedExtensions() { return allowedExtensions; }
    public void setAllowedExtensions(String[] allowedExtensions) { this.allowedExtensions = allowedExtensions; }
    public Long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(Long maxFileSize) { this.maxFileSize = maxFileSize; }
    public String getWatermarkText() { return watermarkText; }
    public void setWatermarkText(String watermarkText) { this.watermarkText = watermarkText; }
    public Integer getWatermarkFontSize() { return watermarkFontSize; }
    public void setWatermarkFontSize(Integer watermarkFontSize) { this.watermarkFontSize = watermarkFontSize; }
    public Float getWatermarkOpacity() { return watermarkOpacity; }
    public void setWatermarkOpacity(Float watermarkOpacity) { this.watermarkOpacity = watermarkOpacity; }
    public String getWatermarkColor() { return watermarkColor; }
    public void setWatermarkColor(String watermarkColor) { this.watermarkColor = watermarkColor; }
}
