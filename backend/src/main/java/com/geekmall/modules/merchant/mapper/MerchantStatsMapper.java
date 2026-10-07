package com.geekmall.modules.merchant.mapper;

import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 商家域聚合查询 Mapper。
 *
 * <p>把「按状态计数」「日序列」这类跨行聚合下沉到数据库，
 * 避免在 Java 侧把整表拉出来再统计。</p>
 */
@Mapper
public interface MerchantStatsMapper {

    /** 商品按商家侧状态计数：返回 [{merchantStatus, cnt}]。 */
    @Select("SELECT merchant_status AS merchantStatus, COUNT(*) AS cnt FROM pms_product "
            + "WHERE shop_id = #{shopId} AND deleted = 0 GROUP BY merchant_status")
    List<Map<String, Object>> productStatusCounts(@Param("shopId") Long shopId);

    /** 商品经营汇总：上架数、累计销量、预警数、售罄数。 */
    @Select("SELECT "
            + "  SUM(CASE WHEN merchant_status = 'on' THEN 1 ELSE 0 END) AS onSale, "
            + "  SUM(CASE WHEN merchant_status <> 'trash' THEN 1 ELSE 0 END) AS alive, "
            + "  SUM(CASE WHEN merchant_status <> 'trash' AND stock > 0 AND stock <= safe_stock THEN 1 ELSE 0 END) AS warn, "
            + "  SUM(CASE WHEN merchant_status <> 'trash' AND stock = 0 THEN 1 ELSE 0 END) AS `out`, "
            + "  COALESCE(SUM(CASE WHEN merchant_status <> 'trash' THEN sales ELSE 0 END), 0) AS sales "
            + "FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0")
    Map<String, Object> productSummary(@Param("shopId") Long shopId);

    /** 库存预警商品（低于安全线，含售罄）。 */
    @Select("SELECT id, shop_id AS shopId, title, cover, spec, brand_name AS brandName, "
            + "category_name AS categoryName, price, cost, stock, safe_stock AS safeStock, sales, views, "
            + "status, merchant_status AS merchantStatus, tags "
            + "FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND merchant_status <> 'trash' AND stock <= safe_stock "
            + "ORDER BY stock ASC")
    List<Map<String, Object>> stockAlerts(@Param("shopId") Long shopId);

    /** 商品筛选下拉：分类 / 品牌去重。 */
    @Select("SELECT DISTINCT category_name FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND merchant_status <> 'trash' AND category_name IS NOT NULL")
    List<String> distinctCategories(@Param("shopId") Long shopId);

    @Select("SELECT DISTINCT brand_name FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND merchant_status <> 'trash' AND brand_name IS NOT NULL")
    List<String> distinctBrands(@Param("shopId") Long shopId);

    /** 近 N 天成交额日序列：返回 [{day, amount, orders}]。 */
    @Select("SELECT DATE(create_time) AS day, COALESCE(SUM(pay_amount), 0) AS amount, COUNT(*) AS orders "
            + "FROM oms_order WHERE shop_id = #{shopId} AND deleted = 0 AND status <> 5 "
            + "AND create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) "
            + "GROUP BY DATE(create_time) ORDER BY day")
    List<Map<String, Object>> dailySeries(@Param("shopId") Long shopId, @Param("days") int days);

    /** 订单按状态计数：返回 [{status, cnt}]。 */
    @Select("SELECT status AS status, COUNT(*) AS cnt FROM oms_order "
            + "WHERE shop_id = #{shopId} AND deleted = 0 GROUP BY status")
    List<Map<String, Object>> orderStatusCounts(@Param("shopId") Long shopId);

    /** 店铺成交汇总：累计成交额、累计订单数、今日成交额/单量、待发货数。 */
    @Select("SELECT COALESCE(SUM(pay_amount), 0) AS totalAmount, COUNT(*) AS totalCount, "
            + "  SUM(CASE WHEN create_time >= CURDATE() AND status <> 5 THEN pay_amount ELSE 0 END) AS todayAmount, "
            + "  SUM(CASE WHEN create_time >= CURDATE() AND status <> 5 THEN 1 ELSE 0 END) AS todayCount, "
            + "  SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS waitShip, "
            + "  SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS shipped, "
            + "  SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS waitReview "
            + "FROM oms_order WHERE shop_id = #{shopId} AND deleted = 0")
    Map<String, Object> orderSummary(@Param("shopId") Long shopId);

    /** 待发货订单数（含临期预警，用于发货中心）。 */
    @Select("SELECT COUNT(*) FROM oms_order WHERE shop_id = #{shopId} AND deleted = 0 AND status = 1")
    long waitShipCount(@Param("shopId") Long shopId);

    /** 售后待处理数（商家侧）。 */
    @Select("SELECT COUNT(*) FROM oms_aftersale WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND " + MerchantAftersaleStatus.STATUS_SQL + " = '" + MerchantAftersaleStatus.PENDING + "'")
    long aftersalePendingCount(@Param("shopId") Long shopId);

    /** 「售后中」订单数：存在未终结售后工单的订单。 */
    @Select("SELECT COUNT(DISTINCT order_no) FROM oms_aftersale WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND " + MerchantAftersaleStatus.STATUS_SQL + " IN " + MerchantAftersaleStatus.NON_FINAL_IN)
    long afterOrderCount(@Param("shopId") Long shopId);

    /** 待回复评价数（未回复且未被忽略）。 */
    @Select("SELECT COUNT(*) FROM pms_review WHERE shop_id = #{shopId} AND deleted = 0 "
            + "AND (reply IS NULL OR reply = '') AND ignored = 0")
    long reviewWaitCount(@Param("shopId") Long shopId);

    /** 进行中营销活动数。 */
    @Select("SELECT COUNT(*) FROM mms_promotion WHERE shop_id = #{shopId} AND deleted = 0 AND status = 'running'")
    long runningPromotionCount(@Param("shopId") Long shopId);

    /** 售后按商家状态计数：返回 [{merchantStatus, cnt}]，口径见 {@link MerchantAftersaleStatus#STATUS_SQL}。 */
    @Select("SELECT " + MerchantAftersaleStatus.STATUS_SQL + " AS merchantStatus, COUNT(*) AS cnt "
            + "FROM oms_aftersale WHERE shop_id = #{shopId} AND deleted = 0 "
            + "GROUP BY " + MerchantAftersaleStatus.STATUS_SQL)
    List<Map<String, Object>> aftersaleStatusCounts(@Param("shopId") Long shopId);

    /** 发货超时预警数：待发货且支付已超过 N 小时。 */
    @Select("SELECT COUNT(*) FROM oms_order WHERE shop_id = #{shopId} AND deleted = 0 AND status = 1 "
            + "AND pay_time IS NOT NULL AND pay_time <= DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    long shippingLateCount(@Param("shopId") Long shopId, @Param("hours") int hours);

    /** 销量 Top 商品（用于概览）。 */
    @Select("SELECT id, title, cover, spec, brand_name AS brandName, category_name AS categoryName, "
            + "price, stock, safe_stock AS safeStock, sales, views, status, merchant_status AS merchantStatus, tags "
            + "FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0 AND merchant_status <> 'trash' "
            + "ORDER BY sales DESC LIMIT #{limit}")
    List<Map<String, Object>> topProducts(@Param("shopId") Long shopId, @Param("limit") int limit);

    /** 成交额按商品分类分布。 */
    @Select("SELECT category_name AS name, COALESCE(SUM(sales * price), 0) AS amount "
            + "FROM pms_product WHERE shop_id = #{shopId} AND deleted = 0 AND merchant_status <> 'trash' "
            + "AND category_name IS NOT NULL GROUP BY category_name ORDER BY amount DESC")
    List<Map<String, Object>> categorySales(@Param("shopId") Long shopId);

    /** 24 小时下单时段分布：返回 [{hour, cnt}]。 */
    @Select("SELECT HOUR(create_time) AS hour, COUNT(*) AS cnt FROM oms_order "
            + "WHERE shop_id = #{shopId} AND deleted = 0 GROUP BY HOUR(create_time)")
    List<Map<String, Object>> hourlyOrders(@Param("shopId") Long shopId);

    /**
     * 成交额按收货省份分布：返回 [{province, amount}]。
     *
     * <p>收货地址是下单时落库的 JSON 快照，这里用 JSON_VALID 兜住历史脏数据，
     * 避免非 JSON 内容导致整条查询报错。</p>
     */
    @Select("SELECT JSON_UNQUOTE(JSON_EXTRACT(address_snap, '$.province')) AS province, "
            + "COALESCE(SUM(pay_amount), 0) AS amount "
            + "FROM oms_order WHERE shop_id = #{shopId} AND deleted = 0 AND status <> 5 "
            + "AND address_snap IS NOT NULL AND JSON_VALID(address_snap) "
            + "AND JSON_EXTRACT(address_snap, '$.province') IS NOT NULL "
            + "GROUP BY province ORDER BY amount DESC")
    List<Map<String, Object>> regionSales(@Param("shopId") Long shopId);

    /**
     * 新老客结构：返回 [{newCount, repeatCount}]。
     *
     * <p>口径：在店内累计成交 1 次为新客，≥2 次为老客复购。</p>
     */
    @Select("SELECT "
            + "  SUM(CASE WHEN cnt = 1 THEN 1 ELSE 0 END) AS newCount, "
            + "  SUM(CASE WHEN cnt > 1 THEN 1 ELSE 0 END) AS repeatCount "
            + "FROM (SELECT user_id, COUNT(*) AS cnt FROM oms_order "
            + "      WHERE shop_id = #{shopId} AND deleted = 0 AND status <> 5 GROUP BY user_id) t")
    Map<String, Object> customerMix(@Param("shopId") Long shopId);
}
