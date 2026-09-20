package com.wayfare.controller;

import com.wayfare.common.result.Result;
import com.wayfare.dto.UploadVO;
import com.wayfare.service.FileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传控制器
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 单文件上传（自动添加水印）
     */
    @PostMapping("/upload")
    public Result<UploadVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileService.upload(file));
    }

    /**
     * 多文件上传（自动添加水印，最多9张）
     */
    @PostMapping("/batch")
    public Result<List<UploadVO>> batchUpload(@RequestParam("files") MultipartFile[] files) {
        return Result.success(fileService.batchUpload(files));
    }

    /**
     * 头像上传（不添加水印）
     */
    @PostMapping("/avatar")
    public Result<UploadVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.success(fileService.uploadWithoutWatermark(file));
    }
}
