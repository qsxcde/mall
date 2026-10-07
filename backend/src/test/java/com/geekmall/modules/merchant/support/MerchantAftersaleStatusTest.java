package com.geekmall.modules.merchant.support;

import com.geekmall.modules.aftersale.entity.AfterSale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 售后商家侧状态解析单元测试。
 *
 * <p>这里固化的是一条容易被忽略、但会造成真实资损的规则：
 * <b>买家撤销优先于商家侧落库状态</b>。否则买家已撤销的工单会一直显示为「待买家退货」，
 * 商家会无限期等待一件永远不会寄回的商品。</p>
 */
class MerchantAftersaleStatusTest {

    private static AfterSale aftersale(Integer buyerStatus, String merchantStatus) {
        AfterSale item = new AfterSale();
        item.setStatus(buyerStatus);
        item.setMerchantStatus(merchantStatus);
        return item;
    }

    @Nested
    @DisplayName("解析优先级")
    class Resolve {

        @Test
        @DisplayName("入参为 null 时按「待处理」兜底")
        void nullItemIsPending() {
            assertThat(MerchantAftersaleStatus.resolve(null)).isEqualTo(MerchantAftersaleStatus.PENDING);
        }

        @Test
        @DisplayName("已拒绝优先级最高：即使买家侧仍是处理中，也保持 rejected")
        void rejectedWins() {
            assertThat(MerchantAftersaleStatus.resolve(aftersale(0, MerchantAftersaleStatus.REJECTED)))
                    .isEqualTo(MerchantAftersaleStatus.REJECTED);
        }

        @Test
        @DisplayName("买家已撤销优先于商家侧落库状态，避免商家空等退货")
        void buyerCanceledOverridesMerchantStatus() {
            assertThat(MerchantAftersaleStatus.resolve(aftersale(2, MerchantAftersaleStatus.WAIT_RETURN)))
                    .isEqualTo(MerchantAftersaleStatus.CANCELED);
            assertThat(MerchantAftersaleStatus.resolve(aftersale(2, MerchantAftersaleStatus.WAIT_RECEIVE)))
                    .isEqualTo(MerchantAftersaleStatus.CANCELED);
        }

        @Test
        @DisplayName("商家侧已落库状态优先于由买家状态推导")
        void merchantStatusWinsOverDerived() {
            assertThat(MerchantAftersaleStatus.resolve(aftersale(0, MerchantAftersaleStatus.WAIT_RECEIVE)))
                    .isEqualTo(MerchantAftersaleStatus.WAIT_RECEIVE);
        }

        @Test
        @DisplayName("商家侧为空时由买家状态推导")
        void deriveFromBuyerStatus() {
            assertThat(MerchantAftersaleStatus.resolve(aftersale(1, null)))
                    .isEqualTo(MerchantAftersaleStatus.DONE);
            assertThat(MerchantAftersaleStatus.resolve(aftersale(0, null)))
                    .isEqualTo(MerchantAftersaleStatus.PENDING);
            assertThat(MerchantAftersaleStatus.resolve(aftersale(null, null)))
                    .isEqualTo(MerchantAftersaleStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("买家侧状态推导")
    class FromBuyerStatus {

        @Test
        @DisplayName("1 → 已完成，2 → 已撤销，0 / null → 待处理")
        void shouldDerive() {
            assertThat(MerchantAftersaleStatus.fromBuyerStatus(1)).isEqualTo(MerchantAftersaleStatus.DONE);
            assertThat(MerchantAftersaleStatus.fromBuyerStatus(2)).isEqualTo(MerchantAftersaleStatus.CANCELED);
            assertThat(MerchantAftersaleStatus.fromBuyerStatus(0)).isEqualTo(MerchantAftersaleStatus.PENDING);
            assertThat(MerchantAftersaleStatus.fromBuyerStatus(null)).isEqualTo(MerchantAftersaleStatus.PENDING);
            assertThat(MerchantAftersaleStatus.fromBuyerStatus(99)).isEqualTo(MerchantAftersaleStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("SQL 口径与 Java 口径一致性")
    class SqlConsistency {

        @Test
        @DisplayName("未终结集合仅含三种状态，且能在 SQL IN 字面量中找到")
        void nonFinalInMatchesCollection() {
            assertThat(MerchantAftersaleStatus.NON_FINAL)
                    .containsExactly(MerchantAftersaleStatus.PENDING,
                            MerchantAftersaleStatus.WAIT_RETURN,
                            MerchantAftersaleStatus.WAIT_RECEIVE);
            for (String status : MerchantAftersaleStatus.NON_FINAL) {
                assertThat(MerchantAftersaleStatus.NON_FINAL_IN).contains("'" + status + "'");
            }
        }

        @Test
        @DisplayName("已终结状态不得出现在「未终结」集合中")
        void finalStatusesAreExcluded() {
            assertThat(MerchantAftersaleStatus.NON_FINAL)
                    .doesNotContain(MerchantAftersaleStatus.DONE,
                            MerchantAftersaleStatus.REJECTED,
                            MerchantAftersaleStatus.CANCELED);
        }

        @Test
        @DisplayName("STATUS_SQL 的判定顺序必须与 resolve 一致：拒绝 → 撤销 → 落库值 → 推导")
        void statusSqlFollowsSamePrecedence() {
            String sql = MerchantAftersaleStatus.STATUS_SQL;
            int rejected = sql.indexOf("'" + MerchantAftersaleStatus.REJECTED + "'");
            int canceled = sql.indexOf("'" + MerchantAftersaleStatus.CANCELED + "'");
            int fallback = sql.indexOf("merchant_status IS NOT NULL");
            int done = sql.indexOf("WHEN status = 1 THEN '" + MerchantAftersaleStatus.DONE + "'");
            int pending = sql.indexOf("ELSE '" + MerchantAftersaleStatus.PENDING + "'");

            assertThat(rejected).isGreaterThanOrEqualTo(0);
            assertThat(rejected).isLessThan(canceled);
            assertThat(canceled).isLessThan(fallback);
            assertThat(fallback).isLessThan(done);
            assertThat(done).isLessThan(pending);
        }

        @Test
        @DisplayName("全部状态清单用于 Tab 渲染，必须无重复")
        void allStatusesAreUnique() {
            assertThat(MerchantAftersaleStatus.ALL).doesNotHaveDuplicates();
            assertThat(MerchantAftersaleStatus.ALL).hasSize(6);
        }
    }
}
