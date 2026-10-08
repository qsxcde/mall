package com.geekmall.common.constant;

/**
 * Redis Key 统一定义，避免散落在业务代码中造成冲突。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 短信验证码：mall:sms:code:{scene}:{phone} */
    public static final String SMS_CODE = "mall:sms:code:";

    /** 登录令牌（单点会话）：mall:login:token:{userId} */
    public static final String LOGIN_TOKEN = "mall:login:token:";

    /** 商家端登录令牌（与买家端隔离）：mall:merchant:token:{merchantUserId} */
    public static final String MERCHANT_LOGIN_TOKEN = "mall:merchant:token:";

    /**
     * 登录会话失效广播频道（Pub/Sub）。
     *
     * <p>消息体是 userId。用于在登出 / 顶下线 / 改密后，让<b>所有实例</b>立即清掉
     * 自己进程内的令牌缓存，把「跨实例生效延迟」从 30s 降到毫秒级。</p>
     */
    public static final String SESSION_INVALIDATION_CHANNEL = "mall:auth:session:invalidated";

    /** 秒杀库存：mall:seckill:stock:{itemId} */
    public static final String SECKILL_STOCK = "mall:seckill:stock:";

    /** 秒杀一人一单标记：mall:seckill:bought:{itemId}:{userId} */
    public static final String SECKILL_BOUGHT = "mall:seckill:bought:";

    /** 秒杀待落库订单消息流（削峰队列）：mall:seckill:order:stream */
    public static final String SECKILL_ORDER_STREAM = "mall:seckill:order:stream";

    /** 秒杀抢购结果（供前端轮询）：mall:seckill:result:{requestId} */
    public static final String SECKILL_RESULT = "mall:seckill:result:";

    public static String seckillResult(String requestId) {
        return SECKILL_RESULT + requestId;
    }

    /** 下单幂等：mall:order:idempotent:{requestId} */
    public static final String ORDER_IDEMPOTENT = "mall:order:idempotent:";

    /** 订单号号段（多实例安全发号）：mall:order:no:segment */
    public static final String ORDER_NO_SEGMENT = "mall:order:no:segment";

    /** 缓存重建互斥锁：mall:cache:lock:{cacheName}:{cacheKey}（防缓存击穿） */
    public static final String CACHE_LOCK = "mall:cache:lock:";

    /** 商品布隆过滤器位图（防缓存穿透：挡住一定不存在的商品 ID） */
    public static final String BLOOM_PRODUCT = "mall:bloom:product";

    /** 商品布隆过滤器「就绪」标记：不存在时表示位图不可信，应跳过校验 */
    public static final String BLOOM_PRODUCT_READY = "mall:bloom:product:ready";

    public static String smsCode(String scene, String phone) {
        return SMS_CODE + scene + ":" + phone;
    }

    public static String loginToken(Long userId) {
        return LOGIN_TOKEN + userId;
    }

    public static String merchantLoginToken(Long merchantUserId) {
        return MERCHANT_LOGIN_TOKEN + merchantUserId;
    }
}
