package com.geekmall.modules.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * FAQ 出参，对应前端 HelpView 的折叠面板。
 */
@Data
@Schema(description = "常见问题")
public class FaqVO implements Serializable {

    private Long id;

    private String q;

    private String a;
}
