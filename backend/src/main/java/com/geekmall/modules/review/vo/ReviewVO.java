package com.geekmall.modules.review.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价出参：我的评价 / 商品评价列表共用。
 */
@Data
@Schema(description = "评价")
public class ReviewVO implements Serializable {

    private Long id;

    private Long productId;

    private String productTitle;

    private String productCover;

    private String orderNo;

    private Integer scoreDesc;

    private Integer scoreLogistics;

    private Integer scoreService;

    @Schema(description = "综合评分")
    private Integer avgScore;

    private String content;

    private List<String> images;

    private Boolean anonymous;

    @Schema(description = "评价人昵称，匿名时为「匿名用户」")
    private String nickname;

    @Schema(description = "商家回复")
    private String reply;

    private LocalDateTime createTime;
}
