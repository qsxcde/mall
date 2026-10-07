package com.geekmall.modules.merchant.vo;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.geekmall.common.result.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商家端分页结果。
 *
 * <p>在买家侧 {@link PageResult} 的基础上扩展「随列表一起返回的附加数据」
 * （Tab 计数、统计条、图表数据等）。附加字段通过 {@link JsonAnyGetter} 平铺到顶层，
 * 与前端 {@code useTableQuery} 的解构方式（list / total / rest）保持一致。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商家端分页结果")
public class MerchantPageVO<T> extends PageResult<T> {

    @Schema(description = "附加数据（tabs / stats / trend 等）", hidden = true)
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public MerchantPageVO() {
    }

    public MerchantPageVO(java.util.List<T> list, long total, long page, long pageSize) {
        super(list, total, page, pageSize);
    }

    /** 追加一个附加字段。 */
    public MerchantPageVO<T> put(String key, Object value) {
        this.extras.put(key, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras;
    }
}
