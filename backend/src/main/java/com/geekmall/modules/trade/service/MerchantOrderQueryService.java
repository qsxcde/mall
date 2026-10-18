package com.geekmall.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 面向<b>商家端</b>的订单只读查询服务。
 *
 * <h3>为什么需要这一层</h3>
 * <p>商家域的 4 个服务（订单 / 发货 / 财务 / 售后）原本各自直接 {@code import OrderMapper}
 * 与 {@code OrderItemMapper}，自己拼 {@code LambdaQueryWrapper}。这带来三个问题：</p>
 * <ol>
 *   <li><b>SQL 语义没有单一所有者</b>：{@code oms_order} 的表结构、软删条件、店铺隔离条件
 *       （{@code shop_id = ?}）分散在 4 个地方，任何一处漏写 {@code shop_id} 就是<b>越权访问</b>；</li>
 *   <li><b>改表即连锁编译失败</b>：订单表加字段/改字段名，要改 4 个商家服务；</li>
 *   <li><b>分层倒置</b>：商家域是 trade 域的上层消费者，却直接依赖它的数据访问层。</li>
 * </ol>
 *
 * <h3>为什么不直接把现有 {@code OrderService} 扩几个方法</h3>
 * <p>{@code OrderService} 是<b>买家视角</b>的：它的方法签名都带 {@code userId}，
 * 语义是「我的订单」。商家视角的隔离维度是 {@code shopId}，且需要批量装配、
 * 统计聚合等不同能力。两者混在一个接口里，调用方很难判断该传哪个参数，
 * 也容易写出「把 userId 当 shopId 传」这类静默错误。</p>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li><b>只读</b>：本接口不提供写入。订单状态变更必须走 {@code OrderStateMachine}，
 *       否则会绕过 CAS 与状态日志；</li>
 *   <li><b>强制店铺隔离</b>：所有方法都要求 {@code shopId}，内部一律附加
 *       {@code shop_id = ?} 条件，调用方<b>无法</b>漏传；</li>
 *   <li>返回实体而非 VO：商家侧各服务对同一订单的展示字段不同，由调用方转换。</li>
 * </ul>
 */
public interface MerchantOrderQueryService {

    /** 按店铺 + 条件分页查询订单。 */
    IPage<Order> pageByShop(Long shopId, LambdaQueryWrapper<Order> wrapper, long page, long size);

    /** 按店铺 + 条件查询订单列表（不分页，供导出/统计类场景）。 */
    List<Order> listByShop(Long shopId, LambdaQueryWrapper<Order> wrapper);

    /** 按店铺统计符合条件的订单数。 */
    long countByShop(Long shopId, LambdaQueryWrapper<Order> wrapper);

    /** 按店铺 + 订单号查单，不存在返回 null。 */
    Order findByShopAndOrderNo(Long shopId, String orderNo);

    /** 按店铺 + 订单号批量查单（一次查询，避免 N+1）。 */
    List<Order> listByShopAndOrderNos(Long shopId, Collection<String> orderNos);

    /**
     * 更新订单（<b>仅供商家侧维护备注/物流等非状态字段</b>）。
     *
     * <p>状态流转<b>不可</b>走这里 —— 必须经 {@code OrderStateMachine} 以保证 CAS、
     * 状态日志与事件发布。此方法的注释与 ArchUnit 规则共同约束这一点。</p>
     *
     * @return 受影响行数
     */
    int updateNonStatusFields(Order order);

    /** 按订单号批量取订单项，返回 {@code orderNo -> items}。 */
    Map<String, List<OrderItem>> itemsByOrderNos(Collection<String> orderNos);
}
