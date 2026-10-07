package com.geekmall.modules.content.service;

import com.geekmall.modules.content.vo.UploadResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传服务（对象存储）。
 */
public interface FileService {

    /**
     * 上传图片。
     *
     * @param file 文件
     * @param biz  业务目录，如 avatar / review / aftersale
     * @return 可直接访问的 URL
     */
    UploadResultVO upload(MultipartFile file, String biz);
}
