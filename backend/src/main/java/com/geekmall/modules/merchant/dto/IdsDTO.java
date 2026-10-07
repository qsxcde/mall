package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通用批量 ID 入参（忽略评价等场景）。
 */
@Data
@Schema(description = "批量 ID 入参")
public class IdsDTO implements Serializable {

    @NotEmpty(message = "请选择记录")
    private List<String> ids;
}
