package com.geekmall.modules.message.service;

import com.geekmall.modules.message.vo.MessageVO;

import java.util.List;

/**
 * 站内消息服务。
 */
public interface MessageService {

    /** 消息列表，type 为 null 或 "all" 时返回全部。 */
    List<MessageVO> list(Long userId, String type);

    /** 未读数（供顶栏红点）。 */
    long unreadCount(Long userId);

    /** 标记单条已读。 */
    void markRead(Long userId, Long messageId);

    /** 全部标记已读。 */
    void markAllRead(Long userId);

    /**
     * 发送消息。
     *
     * @param userId 接收人；传 0 或 null 表示系统广播
     */
    void send(Long userId, String type, String title, String description, String link);
}
