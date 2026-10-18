package com.geekmall.modules.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FULLTEXT 短语模式的实现层约束。
 *
 * <h3>为什么需要这个单元测试</h3>
 * <p>ngram(2) 把「折叠屏」切成 {@code 折叠} + {@code 叠屏} 两个 token。
 * 普通 BOOLEAN 模式是「各 token 分别 OR」，命中集可能比
 * {@code LIKE '%折叠屏%'} 大 —— <b>语义不再等价</b>。
 * 只有加双引号构成短语查询（要求 token 连续出现）才与 LIKE 等价。</p>
 *
 * <h3>为什么不靠集成测试的命中数来验证</h3>
 * <p>实测发现：MySQL 8.0 的 ngram 分词器在 {@code MATCH ... AGAINST(?)} 参数绑定下，
 * 两种模式返回了<b>相同的命中集</b>，因此「造一个共享 token 的干扰商品、看会不会被误召回」
 * 这条路在本环境下无法区分两种模式 —— <b>注入「去掉双引号」的 bug 后集成测试仍然全绿</b>。</p>
 *
 * <p>所以约束必须落在<b>参数构造</b>这一层：直接捕获传给 Mapper 的 phrase 字符串，
 * 断言它带双引号。这条断言对 bug 是敏感的（去掉引号立刻失败），也不依赖数据库行为。</p>
 */
class FulltextPhraseModeTest {

    private ProductMapper mapper;

    private FulltextProductSearchService service;

    @BeforeEach
    void setUp() {
        mapper = org.mockito.Mockito.mock(ProductMapper.class);
        service = new FulltextProductSearchService(mapper);
        when(mapper.countFulltext(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
    }

    @Test
    @DisplayName("关键词被包成短语（带双引号）后再传给 SQL —— 这是与 LIKE 语义等价的唯一方式")
    void keywordIsWrappedAsPhrase() {
        service.search(query("折叠屏"));

        ArgumentCaptor<String> phrase = ArgumentCaptor.forClass(String.class);
        verify(mapper).countFulltext(phrase.capture(), any(), any(), any(), any(), any(), any(), any());

        assertThat(phrase.getValue())
                .as("ngram 下必须用短语模式，否则会拆成多 token 做 OR，命中集比 LIKE 大")
                .isEqualTo("\"折叠屏\"");
    }

    @Test
    @DisplayName("关键词里已有的双引号会被剔除，避免用户输入破坏短语结构")
    void quotesInKeywordAreStripped() {
        service.search(query("折叠\"屏"));

        ArgumentCaptor<String> phrase = ArgumentCaptor.forClass(String.class);
        verify(mapper).countFulltext(phrase.capture(), any(), any(), any(), any(), any(), any(), any());

        assertThat(phrase.getValue()).as("应只保留最外层一对双引号，内部引号被移除").isEqualTo("\"折叠屏\"");
    }

    @Test
    @DisplayName("关键词前后空白被裁剪后再包引号")
    void keywordIsTrimmed() {
        service.search(query("  折叠屏  "));

        ArgumentCaptor<String> phrase = ArgumentCaptor.forClass(String.class);
        verify(mapper).countFulltext(phrase.capture(), any(), any(), any(), any(), any(), any(), any());

        assertThat(phrase.getValue()).isEqualTo("\"折叠屏\"");
    }

    @Test
    @DisplayName("无关键词时不走 MATCH（全文索引帮不上忙，退回普通列表查询）")
    void blankKeywordSkipsFulltextPath() {
        when(mapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.search(query("   "));

        verify(mapper, org.mockito.Mockito.never())
                .countFulltext(anyString(), any(), any(), any(), any(), any(), any(), any());
        // 走的是普通分页查询
        verify(mapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("total 为 0 时不再发起取 id 的查询（省一次全表扫描）")
    void zeroTotalSkipsIdQuery() {
        when(mapper.countFulltext(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        var result = service.search(query("不存在的词"));

        assertThat(result.getTotal()).isZero();
        assertThat(result.getList()).isEmpty();
        verify(mapper, org.mockito.Mockito.never())
                .searchFulltextIds(
                        anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("引擎标识固定为 mysql-fulltext")
    void engineName() {
        assertThat(service.engineName()).isEqualTo("mysql-fulltext");
    }

    private ProductQueryDTO query(String keyword) {
        ProductQueryDTO q = new ProductQueryDTO();
        q.setPage(1);
        q.setPageSize(8);
        q.setKeyword(keyword);
        return q;
    }
}
