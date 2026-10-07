package com.geekmall.common.result;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.exception.BizException;
import com.geekmall.modules.product.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应包装单元测试。
 *
 * <p>前端以 {@code code === 0} 判定成功，因此成功码与失败码的取值属于对外契约。</p>
 */
class ResultTest {

    @Test
    @DisplayName("ok() 返回成功码且无数据")
    void okWithoutData() {
        Result<Void> result = Result.ok();
        assertThat(result.getCode()).isZero();
        assertThat(result.getMsg()).isEqualTo("success");
        assertThat(result.getData()).isNull();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("ok(data) 携带业务数据")
    void okWithData() {
        Result<String> result = Result.ok("GM202610010001");
        assertThat(result.getData()).isEqualTo("GM202610010001");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("fail(ResultCode) 使用枚举自带的码与文案")
    void failWithResultCode() {
        Result<Void> result = Result.fail(ResultCode.PARAM_ERROR);
        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).isEqualTo("参数校验失败");
        assertThat(result.getData()).isNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("fail(code, msg) 支持自定义码与文案")
    void failWithCustomCode() {
        Result<Void> result = Result.fail(2001, "库存不足");
        assertThat(result.getCode()).isEqualTo(2001);
        assertThat(result.getMsg()).isEqualTo("库存不足");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ResultCode 成功码为 0，401 用于前端登录态判定")
    void resultCodeContract() {
        assertThat(ResultCode.SUCCESS.getCode()).isZero();
        assertThat(ResultCode.UNAUTHORIZED.getCode()).isEqualTo(401);
        assertThat(ResultCode.NOT_FOUND.getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("BizException 保留业务码，供全局异常处理器映射响应")
    void bizExceptionKeepsCode() {
        BizException e = BizException.of(ResultCode.NOT_FOUND);
        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).isEqualTo("资源不存在");

        BizException custom = new BizException(ResultCode.NOT_FOUND, "订单不存在：GM1");
        assertThat(custom.getCode()).isEqualTo(404);
        assertThat(custom.getMessage()).isEqualTo("订单不存在：GM1");

        BizException raw = new BizException(999, "自定义");
        assertThat(raw.getCode()).isEqualTo(999);
    }

    @Test
    @DisplayName("PageResult.of 透传分页元数据与记录")
    void pageResultOf() {
        Page<Product> page = new Page<>(2, 10);
        Product product = new Product();
        product.setId(7L);
        page.setRecords(List.of(product));
        page.setTotal(42);

        PageResult<Product> result = PageResult.of(page);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getId()).isEqualTo(7L);
        assertThat(result.getTotal()).isEqualTo(42);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("PageResult.of(page, mapper) 把实体分页映射为 VO 分页")
    void pageResultOfWithMapper() {
        Page<Product> page = new Page<>(1, 5);
        Product first = new Product();
        first.setId(1L);
        first.setTitle("极客手机");
        Product second = new Product();
        second.setId(2L);
        second.setTitle("极客耳机");
        page.setRecords(List.of(first, second));
        page.setTotal(2);

        PageResult<String> result = PageResult.of(page, Product::getTitle);

        assertThat(result.getList()).containsExactly("极客手机", "极客耳机");
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("records 为 null 时映射为空白列表，而不是抛 NPE")
    void pageResultShouldTolerateNullRecords() {
        Page<Product> page = new Page<>(1, 5);
        page.setRecords(null);
        page.setTotal(0);

        PageResult<String> result = PageResult.of(page, Product::getTitle);

        assertThat(result.getList()).isEmpty();
    }

    @Test
    @DisplayName("PageResult.empty 返回空列表与零总数")
    void pageResultEmpty() {
        PageResult<String> result = PageResult.empty(3, 20);
        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPage()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(20);
    }
}
