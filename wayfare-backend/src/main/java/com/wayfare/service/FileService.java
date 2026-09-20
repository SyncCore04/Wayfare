package com.wayfare.service;

import com.wayfare.dto.UploadVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传服务接口
 */
public interface FileService {

    /**
     * 单文件上传（自动添加水印）
     *
     * @param file 上传的文件
     * @return 上传结果
     */
    UploadVO upload(MultipartFile file);

    /**
     * 多文件上传（自动添加水印）
     *
     * @param files 上传的文件数组
     * @return 上传结果列表
     */
    List<UploadVO> batchUpload(MultipartFile[] files);

    /**
     * 上传不添加水印（用于头像等）
     *
     * @param file 上传的文件
     * @return 上传结果
     */
    UploadVO uploadWithoutWatermark(MultipartFile file);
}
