package com.geekmall.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.entity.SeckillSession;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.service.SeckillService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀链路集成测试。
 *
 * <p>秒杀是最容易出现超卖的路径，因此这里针对两道防线都做验证：</p>
 * <ol>
 *   <li>Redis Lua 原子预扣（判库存 + 一人一单 + 扣减 + 续期在同一脚本内）</li>
 *   <li>DB 的 {@code stock > 0} 条件扣减（预扣与 DB 不一致时的兜底）</li>
 * </ol>
 */
class SeckillFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillItemMapper seckillItemMapper;
    @Autowired
    private SeckillSessionMapper seckillSessionMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserService userService;

    private record Buyer(long userId, long addressId) {
    }

    private long createSession() {
        SeckillSession session = new SeckillSession();
        session.setSessionTime("20:00");
        session.setLabel("集成测试场次");
        session.setState("running");
        session.setSort(99);
        seckillSessionMapper.insert(session);
        return session.getId();
    }

    private Product createProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        // category_id / category_key / parent_key 在 pms_product 中为 NOT NULL，必须显式赋值
        product.setCategoryId(1L);
        product.setCategoryKey("integration-seckill");
        product.setParentKey("integration");
        product.setCover("https://img/seckill/" + title + ".png");
        product.setSpec("秒杀版");
        product.setPrice(new BigDecimal(price));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("秒杀测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(1);
        product.setIsNew(0);
        productMapper.insert(product);
        return product;
    }

    private SeckillItem createSeckillItem(long sessionId, long productId, int activityStock, String seckillPrice) {
        SeckillItem item = new SeckillItem();
        item.setSessionId(sessionId);
        item.setProductId(productId);
        item.setSeckillPrice(new BigDecimal(seckillPrice));
        item.setOldPrice(new BigDecimal("999.00"));
        item.setStock(activityStock);
        item.setTotal(activityStock);
        item.setSold(0);
        item.setTip("集成测试秒杀");
        item.setNotStart(0);
        seckillItemMapper.insert(item);
        return item;
    }

    private Buyer createBuyer(String phone) {
        SysUser user = new SysUser();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setPassword("$2a$10$integrationtestplaceholderintegrationtestplaceholder");
        user.setNickname("秒杀买家");
        user.setStatus(1);
        user.setLevelId(1L);
        user.setPoints(0);
        user.setGrowth(0);
        sysUserMapper.insert(user);

        AddressDTO address = new AddressDTO();
        address.setName("秒杀收货人");
        address.setPhone("13800000002");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetail("秒杀大道 1 号");
        address.setIsDefault(true);
        return new Buyer(user.getId(), userService.addAddress(user.getId(), address));
    }

    private Order reloadOrder(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
    }

    @Test
    @DisplayName("秒杀成功：按秒杀价建单，活动库存与商品库存同步扣减")
    void shouldGrabSuccessfully() {
        long sessionId = createSession();
        Product product = createProduct("秒杀商品A", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        Buyer buyer = createBuyer(randomPhone());

        String orderNo = seckillService.grab(buyer.userId(), item.getId(), buyer.addressId());

        assertThat(orderNo).startsWith("GM");
        Order order = reloadOrder(orderNo);
        assertThat(order).isNotNull();
        assertThat(order.getPayAmount()).isEqualByComparingTo("199.00");
        assertThat(order.getGoodsAmount()).isEqualByComparingTo("199.00");

        SeckillItem after = seckillItemMapper.selectById(item.getId());
        assertThat(after.getStock()).isEqualTo(4);
        assertThat(after.getSold()).isEqualTo(1);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("一人一单：同一用户第二次抢购被拒绝，且不再扣减库存")
    void shouldRejectDuplicateGrabBySameUser() {
        long sessionId = createSession();
        Product product = createProduct("秒杀商品B", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        Buyer buyer = createBuyer(randomPhone());

        seckillService.grab(buyer.userId(), item.getId(), buyer.addressId());

        assertThatThrownBy(() -> seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限购");

        assertThat(seckillItemMapper.selectById(item.getId()).getStock()).isEqualTo(4);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("库存耗尽：活动库存为 1 时第二个用户抢购失败")
    void shouldRejectWhenSoldOut() {
        long sessionId = createSession();
        Product product = createProduct("秒杀商品C", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 1, "199.00");
        Buyer first = createBuyer(randomPhone());
        Buyer second = createBuyer(randomPhone());

        seckillService.grab(first.userId(), item.getId(), first.addressId());

        assertThatThrownBy(() -> seckillService.grab(second.userId(), item.getId(), second.addressId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("抢光");

        assertThat(seckillItemMapper.selectById(item.getId()).getStock()).isZero();
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(9);
    }

    @Test
    @DisplayName("未开始的场次不能抢购")
    void shouldRejectNotStarted() {
        long sessionId = createSession();
        Product product = createProduct("秒杀商品D", 10, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), 5, "199.00");
        item.setNotStart(1);
        seckillItemMapper.updateById(item);
        Buyer buyer = createBuyer(randomPhone());

        assertThatThrownBy(() -> seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未开始");

        assertThat(seckillItemMapper.selectById(item.getId()).getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("秒杀商品不存在时返回 404 业务异常")
    void shouldRejectMissingItem() {
        Buyer buyer = createBuyer(randomPhone());

        assertThatThrownBy(() -> seckillService.grab(buyer.userId(), -1L, buyer.addressId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("并发抢购：3 件库存面对 6 个用户，恰好 3 人成功且不超卖")
    void concurrentGrabShouldNotOversell() throws InterruptedException {
        int activityStock = 3;
        int buyers = 6;

        long sessionId = createSession();
        Product product = createProduct("秒杀并发商品", 100, "999.00");
        SeckillItem item = createSeckillItem(sessionId, product.getId(), activityStock, "199.00");

        List<Buyer> users = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            users.add(createBuyer(randomPhone()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(buyers);
        List<String> succeeded = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());

        try {
            for (Buyer buyer : users) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        succeeded.add(seckillService.grab(buyer.userId(), item.getId(), buyer.addressId()));
                    } catch (Throwable t) {
                        failed.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finishGate.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded)
                .as("成功人数必须恰好等于活动库存，既不能超卖也不能少卖")
                .hasSize(activityStock);
        assertThat(failed).hasSize(buyers - activityStock);
        assertThat(failed).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));

        SeckillItem after = seckillItemMapper.selectById(item.getId());
        assertThat(after.getStock()).isZero();
        assertThat(after.getSold()).isEqualTo(activityStock);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(100 - activityStock);
    }
}
