package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import lombok.Data;

/**
 * 规格值出参：值与展示图。
 */
@Data
@Schema(description = "规格值")
public class SpecValueVO implements Serializable {

    @Schema(description = "规格值：黑色 / XL / 512G")
    private String value;

    @Schema(description = "该规格值的展示图（如颜色图）")
    private String image;

    public SpecValueVO() {}

    public SpecValueVO(String value, String image) {
        this.value = value;
        this.image = image;
    }
}
