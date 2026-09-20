package com.wayfare.service.impl;

import com.wayfare.common.config.FileStorageProperties;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.UploadVO;
import com.wayfare.service.FileService;
import com.wayfare.util.WatermarkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传服务实现
 * 支持多图上传、类型校验、大小校验、文字水印、本地存储
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private final FileStorageProperties storageProperties;

    public FileServiceImpl(FileStorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @Override
    public UploadVO upload(MultipartFile file) {
        return doUpload(file, true);
    }

    @Override
    public List<UploadVO> batchUpload(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择要上传的文件");
        }
        if (files.length > 9) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "单次最多上传9张图片");
        }
        List<UploadVO> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(doUpload(file, true));
        }
        log.info("批量上传完成，共 {} 张图片", results.size());
        return results;
    }

    @Override
    public UploadVO uploadWithoutWatermark(MultipartFile file) {
        return doUpload(file, false);
    }

    /**
     * 执行文件上传
     */
    private UploadVO doUpload(MultipartFile file, boolean addWatermark) {
        // 1. 空文件校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }

        // 2. 文件大小校验
        long size = file.getSize();
        if (size > storageProperties.getMaxFileSize()) {
            throw new BusinessException(ResultCode.FILE_SIZE_EXCEEDED.getCode(),
                    String.format("文件大小 %.2fMB 超出限制 %.2fMB",
                            size / 1024.0 / 1024.0,
                            storageProperties.getMaxFileSize() / 1024.0 / 1024.0));
        }

        // 3. 文件类型校验
        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        String extension = getFileExtension(originalFilename);

        boolean typeAllowed = Arrays.asList(storageProperties.getAllowedTypes()).contains(contentType);
        boolean extAllowed = Arrays.asList(storageProperties.getAllowedExtensions()).contains(extension.toLowerCase());
        if (!typeAllowed && !extAllowed) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                    String.format("不支持的文件类型: %s (.%s)，仅支持 %s",
                            contentType, extension,
                            String.join("/", storageProperties.getAllowedExtensions())));
        }

        try {
            // 4. 生成存储路径（按日期分目录）
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uploadDir = storageProperties.getUploadDir() + "/" + datePath;
            Path dirPath = Paths.get(uploadDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            // 5. 生成唯一文件名
            String newFileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            Path targetPath = dirPath.resolve(newFileName);

            // 6. 保存原始文件
            File tempFile = File.createTempFile("upload_", "." + extension);
            file.transferTo(tempFile);

            // 7. 添加水印（图片文件）
            File finalFile;
            if (addWatermark && isImageFile(extension)) {
                finalFile = targetPath.toFile();
                boolean watermarkResult = WatermarkUtil.addTextWatermark(
                        tempFile, finalFile,
                        storageProperties.getWatermarkText(),
                        storageProperties.getWatermarkFontSize(),
                        storageProperties.getWatermarkOpacity(),
                        storageProperties.getWatermarkColor()
                );
                if (!watermarkResult) {
                    // 水印失败时直接保存原图
                    Files.copy(tempFile.toPath(), targetPath);
                    log.warn("水印添加失败，保存原图: {}", newFileName);
                }
                tempFile.delete();
            } else {
                finalFile = targetPath.toFile();
                Files.copy(tempFile.toPath(), targetPath);
                tempFile.delete();
            }

            // 8. 获取图片尺寸
            int[] dimensions = {0, 0};
            if (isImageFile(extension)) {
                dimensions = WatermarkUtil.getImageDimensions(finalFile);
            }

            // 9. 构建返回结果
            String fileUrl = storageProperties.getAccessPrefix() + "/" + datePath + "/" + newFileName;
            UploadVO vo = new UploadVO(newFileName, fileUrl, size, contentType);
            vo.setWidth(dimensions[0]);
            vo.setHeight(dimensions[1]);

            log.info("文件上传成功: {} -> {} ({}KB)", originalFilename, fileUrl, size / 1024);
            return vo;

        } catch (IOException e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1);
        }
        return "";
    }

    /**
     * 判断是否为图片文件
     */
    private boolean isImageFile(String extension) {
        if (!StringUtils.hasText(extension)) {
            return false;
        }
        String ext = extension.toLowerCase();
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("gif") || ext.equals("bmp") || ext.equals("webp");
    }
}
