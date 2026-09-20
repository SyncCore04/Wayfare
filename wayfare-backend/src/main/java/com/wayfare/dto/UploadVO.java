package com.wayfare.dto;

import java.io.Serializable;

/**
 * 文件上传返回结果
 */
public class UploadVO implements Serializable {

    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String contentType;
    private Integer width;
    private Integer height;

    public UploadVO() {}

    public UploadVO(String fileName, String fileUrl, Long fileSize, String contentType) {
        this.fileName = fileName;
        this.fileUrl = fileUrl;
        this.fileSize = fileSize;
        this.contentType = contentType;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
}
