package com.geekmall.modules.marketing.dto;

/**
 * 批量落库中单条消息的处理结果。
 *
 * <p>{@code success=false} 表示业务性失败（商品不存在 / 活动未开始 / 库存桶信息缺失），
 * 消费端需要回补 Redis 预扣并写入 FAILED 终态；系统异常不会走到这里，
 * 而是由批量事务整体回滚后逐条重试。</p>
 */
public record BatchOrderOutcome(String requestId, Long userId, boolean success, String orderNo, String message) {

    public static BatchOrderOutcome success(String requestId, Long userId, String orderNo) {
        return new BatchOrderOutcome(requestId, userId, true, orderNo, "抢购成功");
    }

    public static BatchOrderOutcome failed(String requestId, Long userId, String message) {
        return new BatchOrderOutcome(requestId, userId, false, null, message);
    }
}
