package com.geekmall.modules.content.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.content.service.FileService;
import com.geekmall.modules.content.vo.UploadResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口，供头像、评价图、售后凭证使用。
 */
@Tag(name = "15-文件上传", description = "图片上传（MinIO）")
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Operation(summary = "上传图片",
            description = "biz 取值：avatar 头像 / review 评价图 / aftersale 售后凭证 / common 通用")
    @PostMapping
    public Result<UploadResultVO> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(defaultValue = "common") String biz) {
        return Result.ok(fileService.upload(file, biz));
    }
}
