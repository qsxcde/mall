package com.geekmall.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件上传结果。
 */
@Data
@Builder
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

    /**
     * 是否为降级结果（对象存储不可用，已改存本地磁盘）。
     *
     * <p>刻意做成「可识别的降级态」：静默降级会让用户以为文件进了对象存储，
     * 而实际落在某个实例的本地磁盘上 —— 多实例部署时其他节点根本访问不到。
     * 标记出来，排查问题时才有据可依。</p>
     *
     * <p>未降级时为 {@code null}，配合 {@code default-property-inclusion: non_null}
     * 不会出现在响应里，因此正常上传的响应结构完全不变。</p>
     */
    @Schema(description = "是否为降级结果：true 表示主存储不可用，已改用备用存储")
    private Boolean degraded;
}
