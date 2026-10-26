package com.geekmall.modules.product.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 深分页防护单测。
 *
 * <p>MyBatis-Plus 的 {@code maxLimit} 只限制单页条数，拦不住超大 OFFSET；
 * 这里把「page × pageSize ≤ 10 万」固化成参数级校验，避免深分页请求进入 SQL。</p>
 */
@DisplayName("ProductQueryDTO 分页深度校验")
class ProductQueryDTOTest {

    @Test
    @DisplayName("正常分页通过")
    void shouldAllowNormalPaging() {
        ProductQueryDTO dto = new ProductQueryDTO();
        dto.setPage(100);
        dto.setPageSize(100);

        assertThat(dto.isPageDepthAllowed()).isTrue();
    }

    @Test
    @DisplayName("超过 10 万 OFFSET 被拒绝")
    void shouldRejectDeepPaging() {
        ProductQueryDTO dto = new ProductQueryDTO();
        dto.setPage(2000);
        dto.setPageSize(100);

        assertThat(dto.isPageDepthAllowed()).isFalse();
    }

    @Test
    @DisplayName("空值按默认值计算，不误伤")
    void shouldTolerateNulls() {
        ProductQueryDTO dto = new ProductQueryDTO();
        dto.setPage(null);
        dto.setPageSize(null);

        assertThat(dto.isPageDepthAllowed()).isTrue();
    }
}
