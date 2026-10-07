package com.geekmall.modules.message.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.message.service.MessageService;
import com.geekmall.modules.message.vo.MessageVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内消息接口，对应前端 MessagesView 与顶栏未读红点。
 */
@Tag(name = "16-消息中心", description = "消息列表 / 未读数 / 标记已读")
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "消息列表", description = "type：all / order / logistics / coupon / system")
    @GetMapping
    public Result<List<MessageVO>> list(@RequestParam(required = false) String type) {
        return Result.ok(messageService.list(SecurityUtils.getUserId(), type));
    }

    @Operation(summary = "未读数", description = "供顶栏红点使用")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(messageService.unreadCount(SecurityUtils.getUserId()));
    }

    @Operation(summary = "标记单条已读")
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        messageService.markRead(SecurityUtils.getUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        messageService.markAllRead(SecurityUtils.getUserId());
        return Result.ok();
    }
}
