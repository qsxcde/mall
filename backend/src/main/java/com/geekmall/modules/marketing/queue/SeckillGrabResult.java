package com.geekmall.modules.marketing.queue;

/**
 * 秒杀抢购结果（异步模式下供前端轮询）。
 *
 * <p>异步化的核心代价是**引入了一个中间态**：请求已经受理、但订单还没建出来。
 * 这个中间态必须能被用户看见，否则前端只能一直转圈 —— {@link Status#QUEUED} 就是它。</p>
 *
 * <p>带上 {@code userId} 是为了让查询接口能校验归属，避免拿别人的 requestId
 * 查到订单号（虽然 requestId 是随机串不可枚举，但多一道校验不亏）。</p>
 */
public record SeckillGrabResult(String requestId, Long userId, Status status, String orderNo, String message) {

    public enum Status {
        /** 已受理，等待落库（用户应继续轮询）。 */
        QUEUED,
        /** 落库成功，{@link #orderNo} 为订单号。 */
        SUCCESS,
        /** 落库失败（库存不足 / 重复抢购 / 系统异常），{@link #message} 为原因。 */
        FAILED
    }

    public static SeckillGrabResult queued(String requestId, Long userId) {
        return new SeckillGrabResult(requestId, userId, Status.QUEUED, null, "排队中，请稍候");
    }

    public static SeckillGrabResult success(String requestId, Long userId, String orderNo) {
        return new SeckillGrabResult(requestId, userId, Status.SUCCESS, orderNo, "抢购成功");
    }

    public static SeckillGrabResult failed(String requestId, Long userId, String message) {
        return new SeckillGrabResult(requestId, userId, Status.FAILED, null, message);
    }

    /** 是否已到终态（成功或失败）。消费端据此判断重复投递的消息是否已处理过。 */
    public boolean isFinished() {
        return status != Status.QUEUED;
    }
}
