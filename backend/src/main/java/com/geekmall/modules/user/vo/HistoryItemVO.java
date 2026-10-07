package com.geekmall.modules.user.vo;

import com.geekmall.modules.product.vo.ProductCardVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 浏览足迹项：商品卡片 + 浏览时间。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "浏览足迹项")
public class HistoryItemVO extends ProductCardVO {

    @Schema(description = "最近浏览时间")
    private LocalDateTime viewTime;
}
