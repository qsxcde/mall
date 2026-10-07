package com.geekmall.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件上传结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "上传结果")
public class UploadResultVO implements Serializable {

    @Schema(description = "可直接访问的文件 URL")
    private String url;

    @Schema(description = "对象存储中的对象名")
    private String objectName;

    private Long size;

    private String contentType;
}
