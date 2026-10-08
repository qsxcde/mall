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
 *
 * <p><b>为什么还要带 {@code traceId}</b>：MDC 是 ThreadLocal，跨线程池都传不过去，
 * 更不用说跨队列。若不在消息体里捎上链路 ID，消费端只能生成一个新的 traceId，
 * 于是「用户抢购入队」与「后台落库」变成两条互不相干的日志——而这恰恰是
 * 秒杀削峰链路上最需要被串起来的一段。它是<b>可选</b>字段：缺失时消费端照常工作，
 * 只是这段链路退化为无关联。</p>
 *
 * <p><b>为什么必须带 {@code bucketNo}</b>：库存分桶后，DB 扣减对象是某一个具体的桶行，
 * 而选桶用的是「随机起点 + 顺序探测」——<b>不可复现</b>（可复现的只有 {@code userId % N}
 * 那种固定路由，但它会假售罄）。消费端拿不到桶号就只能瞎猜该扣哪一桶，
 * 回补也就还不回原来那一行。不分桶商品该字段为 {@code null}。</p>
 */
public record SeckillOrderMessage(String requestId, Long userId, Long itemId, Long addressId,
                                  String traceId, Integer bucketNo) {

    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_ITEM_ID = "itemId";
    private static final String FIELD_ADDRESS_ID = "addressId";
    private static final String FIELD_TRACE_ID = "traceId";
    private static final String FIELD_BUCKET_NO = "bucketNo";

    /** 不分桶路径的便利构造：桶号固定为 {@code null}。 */
    public SeckillOrderMessage(String requestId, Long userId, Long itemId, Long addressId, String traceId) {
        this(requestId, userId, itemId, addressId, traceId, null);
    }

    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_REQUEST_ID, requestId);
        fields.put(FIELD_USER_ID, String.valueOf(userId));
        fields.put(FIELD_ITEM_ID, String.valueOf(itemId));
        fields.put(FIELD_ADDRESS_ID, String.valueOf(addressId));
        // 为空时不写字段：既保持消息精简，也让读取端能自然区分「老消息」与「新消息」
        if (traceId != null && !traceId.isBlank()) {
            fields.put(FIELD_TRACE_ID, traceId);
        }
        if (bucketNo != null) {
            fields.put(FIELD_BUCKET_NO, String.valueOf(bucketNo));
        }
        return fields;
    }

    /**
     * 从队列字段还原消息。
     *
     * <p>字段缺失或格式非法时抛异常而不是返回 null —— 这类消息属于生产者写坏的数据，
     * 应当让消费端记为「毒消息」并单独处理，而不是静默跳过留下永远查不到结果的请求。</p>
     *
     * <p>唯一的例外是 {@code traceId} 与 {@code bucketNo}：前者属于观测信息，
     * 后者是分桶后才新增的字段。老消息（不分桶时期写下的）两者都没有，
     * 必须仍能解析，否则一次上线就会把在途消息全变成毒消息。</p>
     */
    public static SeckillOrderMessage fromFields(Map<Object, Object> fields) {
        return new SeckillOrderMessage(
                require(fields, FIELD_REQUEST_ID),
                Long.valueOf(require(fields, FIELD_USER_ID)),
                Long.valueOf(require(fields, FIELD_ITEM_ID)),
                Long.valueOf(require(fields, FIELD_ADDRESS_ID)),
                optional(fields, FIELD_TRACE_ID),
                optionalInt(fields, FIELD_BUCKET_NO));
    }

    private static String require(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("秒杀消息缺少字段：" + key + "，实际字段=" + fields.keySet());
        }
        return String.valueOf(value);
    }

    private static String optional(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer optionalInt(Map<Object, Object> fields, String key) {
        String value = optional(fields, key);
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
