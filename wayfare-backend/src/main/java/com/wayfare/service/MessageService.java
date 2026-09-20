package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.dto.MessageSendDTO;
import com.wayfare.entity.PrivateMessage;

import java.util.Map;

/**
 * 私信服务接口
 */
public interface MessageService {

    /**
     * 发送私信
     *
     * @param senderId 发送者ID
     * @param dto 发送内容
     * @return 发送的消息
     */
    PrivateMessage send(Long senderId, MessageSendDTO dto);

    /**
     * 获取会话消息列表（按时间倒序）
     *
     * @param userId 当前用户ID
     * @param otherUserId 对方用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 消息分页
     */
    IPage<PrivateMessage> getConversationMessages(Long userId, Long otherUserId, Integer pageNum, Integer pageSize);

    /**
     * 获取会话列表（每个会话返回最后一条消息）
     *
     * @param userId 当前用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 会话分页（key为对方用户ID，value为最后一条消息）
     */
    IPage<Map<String, Object>> getConversationList(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取未读消息总数
     *
     * @param userId 用户ID
     * @return 未读消息数
     */
    Long getUnreadCount(Long userId);

    /**
     * 获取与某用户的未读消息数
     */
    Long getUnreadCountWithUser(Long userId, Long otherUserId);

    /**
     * 标记与某用户的会话为已读
     *
     * @param userId 当前用户ID
     * @param otherUserId 对方用户ID
     */
    void markAsRead(Long userId, Long otherUserId);

    /**
     * 生成会话ID（双方ID升序拼接）
     */
    String generateConversationId(Long user1, Long user2);
}
