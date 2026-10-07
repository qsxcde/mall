/**
 * 消息域：站内消息列表、未读数、标记已读，以及订单状态变更的通知投递。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code MessageService}：列表（按类型过滤）、未读数、标记已读 / 全部已读、发送</li>
 *     <li>{@code OrderStatusMessageListener}：监听订单状态变更事件并投递站内消息</li>
 *     <li>{@code MessageController} → /api/v1/messages/**</li>
 * </ul>
 *
 * <h3>两个设计决定</h3>
 * <ol>
 *     <li><b>已读状态单独建表</b>：{@code sys_message.user_id = 0} 表示系统广播，
 *         若把已读写回消息表，会「一人已读、全员已读」。因此用
 *         {@code sys_user_message_read}(user_id, message_id) 唯一索引存回执，标记已读天然幂等。</li>
 *     <li><b>用事务事件解耦，而不是让交易域直接调消息服务</b>：
 *         {@code OrderStateMachine} 只发布 {@code OrderStatusChangedEvent}，
 *         监听器加 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}——
 *         只有订单事务提交后才发消息（不会「订单回滚了但用户收到通知」），
 *         且消息失败不影响下单。将来加短信/App 推送，只需再写一个监听器，交易域零改动。</li>
 * </ol>
 *
 * <p>待实现：真实短信/推送通道、消息模板化（现在是硬编码文案）、批量已读的 SQL 优化（
 * 当前逐条 insert，量大时应改为批量插入）。</p>
 *
 * <p>对应前端：MessagesView、AppHeader 未读红点。</p>
 */
package com.geekmall.modules.message;
