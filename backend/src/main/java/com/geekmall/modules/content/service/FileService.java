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

    /**
     * 把业务表里存的访问地址换成「当前有效」的地址（敏感文件按次重新签发短时效地址）。
     *
     * <p>售后凭证等敏感文件的地址是带签名与过期时间的「不记名凭证」，
     * 不能长期复用：读取时用对象名重新签发，展示型附件（公开地址）原样返回。</p>
     */
    String refreshUrl(String url);
}
