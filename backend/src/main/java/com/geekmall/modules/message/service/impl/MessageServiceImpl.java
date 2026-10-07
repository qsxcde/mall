package com.geekmall.modules.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.message.entity.Message;
import com.geekmall.modules.message.entity.MessageRead;
import com.geekmall.modules.message.mapper.MessageMapper;
import com.geekmall.modules.message.mapper.MessageReadMapper;
import com.geekmall.modules.message.service.MessageService;
import com.geekmall.modules.message.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 站内消息服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private static final String TYPE_ALL = "all";

    private final MessageMapper messageMapper;
    private final MessageReadMapper messageReadMapper;

    @Override
    public List<MessageVO> list(Long userId, String type) {
        List<Message> messages = messageMapper.selectList(visibleWrapper(userId, type));
        Set<Long> readIds = readIds(userId, messages.stream().map(Message::getId).toList());
        return messages.stream().map(message -> toVO(message, readIds.contains(message.getId()))).toList();
    }

    @Override
    public long unreadCount(Long userId) {
        List<Long> ids = messageMapper.selectList(visibleWrapper(userId, null))
                .stream().map(Message::getId).toList();
        if (ids.isEmpty()) {
            return 0L;
        }
        Long read = messageReadMapper.selectCount(new LambdaQueryWrapper<MessageRead>()
                .eq(MessageRead::getUserId, userId)
                .in(MessageRead::getMessageId, ids));
        return ids.size() - (read == null ? 0L : read);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long messageId) {
        Message message = messageMapper.selectById(messageId);
        if (message == null || !visibleTo(message, userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "消息不存在");
        }
        insertReadReceipt(userId, messageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Long userId) {
        List<Long> ids = messageMapper.selectList(visibleWrapper(userId, null))
                .stream().map(Message::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> alreadyRead = readIds(userId, ids);
        ids.stream().filter(id -> !alreadyRead.contains(id))
                .forEach(id -> insertReadReceipt(userId, id));
    }

    @Override
    public void send(Long userId, String type, String title, String description, String link) {
        Message message = new Message();
        message.setUserId(userId == null ? Message.BROADCAST_USER_ID : userId);
        message.setType(StringUtils.hasText(type) ? type : "system");
        message.setTitle(title);
        message.setDescription(description);
        message.setLink(link);
        messageMapper.insert(message);
        log.info("发送站内消息给用户 {}：[{}] {}", message.getUserId(), message.getType(), title);
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 可见范围：自己的消息 + 系统广播（userId = 0）。
     */
    private LambdaQueryWrapper<Message> visibleWrapper(Long userId, String type) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(Message::getUserId, userId)
                .or().eq(Message::getUserId, Message.BROADCAST_USER_ID));
        if (StringUtils.hasText(type) && !TYPE_ALL.equalsIgnoreCase(type)) {
            wrapper.eq(Message::getType, type);
        }
        wrapper.orderByDesc(Message::getId);
        return wrapper;
    }

    private boolean visibleTo(Message message, Long userId) {
        return Message.BROADCAST_USER_ID == message.getUserId() || message.getUserId().equals(userId);
    }

    private Set<Long> readIds(Long userId, Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Set.of();
        }
        return messageReadMapper.selectList(new LambdaQueryWrapper<MessageRead>()
                        .eq(MessageRead::getUserId, userId)
                        .in(MessageRead::getMessageId, messageIds))
                .stream().map(MessageRead::getMessageId).collect(Collectors.toSet());
    }

    /** 唯一索引 (user_id, message_id) 保证重复标记幂等，冲突直接忽略。 */
    private void insertReadReceipt(Long userId, Long messageId) {
        try {
            MessageRead receipt = new MessageRead();
            receipt.setUserId(userId);
            receipt.setMessageId(messageId);
            messageReadMapper.insert(receipt);
        } catch (DuplicateKeyException ignored) {
            log.debug("消息 {} 用户 {} 已标记过已读", messageId, userId);
        }
    }

    private MessageVO toVO(Message message, boolean read) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setType(message.getType());
        vo.setTitle(message.getTitle());
        vo.setDesc(message.getDescription());
        vo.setLink(message.getLink());
        vo.setRead(read);
        vo.setTime(message.getCreateTime());
        return vo;
    }
}
