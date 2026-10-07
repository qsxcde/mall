package com.geekmall.modules.marketing.queue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待落库的秒杀抢购请求（削峰队列的消息体）。
 *
 * <p><b>为什么队列里带的是「请求」而不是「订单」</b>：Redis Lua 预扣决定的是「谁有资格买」，
 * 但资格不等于订单 —— 落库时仍可能因为活动库存扣减失败、地址失效等原因建单失败。
 * 因此消息里带的是完整的原始入参，由消费者在落库阶段重新校验并决定成败。</p>
 *
 * <p><b>为什么必须带 {@code requestId}</b>：队列至少一次投递，消费者可能重复收到同一条消息。
 * {@code requestId} 是消费端幂等键 —— 重复投递时直接跳过，不会重复建单。</p>
 */
public record SeckillOrderMessage(String requestId, Long userId, Long itemId, Long addressId) {

    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_ITEM_ID = "itemId";
    private static final String FIELD_ADDRESS_ID = "addressId";

    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_REQUEST_ID, requestId);
        fields.put(FIELD_USER_ID, String.valueOf(userId));
        fields.put(FIELD_ITEM_ID, String.valueOf(itemId));
        fields.put(FIELD_ADDRESS_ID, String.valueOf(addressId));
        return fields;
    }

    /**
     * 从队列字段还原消息。
     *
     * <p>字段缺失或格式非法时抛异常而不是返回 null —— 这类消息属于生产者写坏的数据，
     * 应当让消费端记为「毒消息」并单独处理，而不是静默跳过留下永远查不到结果的请求。</p>
     */
    public static SeckillOrderMessage fromFields(Map<Object, Object> fields) {
        return new SeckillOrderMessage(
                require(fields, FIELD_REQUEST_ID),
                Long.valueOf(require(fields, FIELD_USER_ID)),
                Long.valueOf(require(fields, FIELD_ITEM_ID)),
                Long.valueOf(require(fields, FIELD_ADDRESS_ID)));
    }

    private static String require(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("秒杀消息缺少字段：" + key + "，实际字段=" + fields.keySet());
        }
        return String.valueOf(value);
    }
}
