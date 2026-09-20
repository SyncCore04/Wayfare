package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.dto.MessageSendDTO;
import com.wayfare.entity.PrivateMessage;
import com.wayfare.security.UserContext;
import com.wayfare.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 私信控制器
 */
@RestController
@RequestMapping("/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 发送私信
     */
    @PostMapping("/send")
    public Result<PrivateMessage> send(@Valid @RequestBody MessageSendDTO dto) {
        Long userId = UserContext.getUserId();
        return Result.success(messageService.send(userId, dto));
    }

    /**
     * 获取会话列表（每个会话显示最后一条消息和未读数）
     */
    @GetMapping("/conversations")
    public Result<IPage<Map<String, Object>>> conversationList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(messageService.getConversationList(userId, pageNum, pageSize));
    }

    /**
     * 获取与某用户的会话消息列表
     */
    @GetMapping("/conversation/{otherUserId}")
    public Result<IPage<PrivateMessage>> conversationMessages(
            @PathVariable Long otherUserId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(messageService.getConversationMessages(userId, otherUserId, pageNum, pageSize));
    }

    /**
     * 获取未读消息总数
     */
    @GetMapping("/unread/count")
    public Result<Long> unreadCount() {
        Long userId = UserContext.getUserId();
        return Result.success(messageService.getUnreadCount(userId));
    }

    /**
     * 获取与某用户的未读消息数
     */
    @GetMapping("/unread/count/{otherUserId}")
    public Result<Long> unreadCountWithUser(@PathVariable Long otherUserId) {
        Long userId = UserContext.getUserId();
        return Result.success(messageService.getUnreadCountWithUser(userId, otherUserId));
    }

    /**
     * 标记与某用户的会话为已读
     */
    @PutMapping("/read/{otherUserId}")
    public Result<Void> markAsRead(@PathVariable Long otherUserId) {
        Long userId = UserContext.getUserId();
        messageService.markAsRead(userId, otherUserId);
        return Result.success();
    }
}
