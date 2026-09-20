package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.MessageSendDTO;
import com.wayfare.entity.PrivateMessage;
import com.wayfare.entity.User;
import com.wayfare.mapper.PrivateMessageMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.service.ContentAuditService;
import com.wayfare.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 私信服务实现
 */
@Service
public class MessageServiceImpl implements MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);

    private final PrivateMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final ContentAuditService contentAuditService;

    public MessageServiceImpl(PrivateMessageMapper messageMapper, UserMapper userMapper,
                               ContentAuditService contentAuditService) {
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.contentAuditService = contentAuditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrivateMessage send(Long senderId, MessageSendDTO dto) {
        // 校验接收者
        User receiver = userMapper.selectById(dto.getReceiverId());
        if (receiver == null) {
            throw new BusinessException(ResultCode.MESSAGE_RECEIVER_NOT_FOUND);
        }
        if (receiver.getStatus() == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "接收者账号已被禁用");
        }
        if (senderId.equals(dto.getReceiverId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不能给自己发私信");
        }

        // 内容审核
        contentAuditService.audit(dto.getContent(), "消息内容");

        // 构建消息
        PrivateMessage message = new PrivateMessage();
        message.setSenderId(senderId);
        message.setReceiverId(dto.getReceiverId());
        message.setConversationId(generateConversationId(senderId, dto.getReceiverId()));
        message.setContent(dto.getContent());
        message.setMsgType(dto.getMsgType() != null ? dto.getMsgType() : 1);
        message.setIsRead(0);
        messageMapper.insert(message);

        log.info("私信发送成功: {} -> {}, content length={}", senderId, dto.getReceiverId(),
                dto.getContent().length());
        return message;
    }

    @Override
    public IPage<PrivateMessage> getConversationMessages(Long userId, Long otherUserId,
                                                           Integer pageNum, Integer pageSize) {
        String conversationId = generateConversationId(userId, otherUserId);
        Page<PrivateMessage> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrivateMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrivateMessage::getConversationId, conversationId)
                .orderByDesc(PrivateMessage::getCreatedAt);
        IPage<PrivateMessage> result = messageMapper.selectPage(page, wrapper);

        // 自动标记为已读（查询会话消息时，将对方发来的消息标记为已读）
        markAsRead(userId, otherUserId);

        return result;
    }

    @Override
    public IPage<Map<String, Object>> getConversationList(Long userId, Integer pageNum, Integer pageSize) {
        // 查询用户参与的所有会话ID（去重）
        LambdaQueryWrapper<PrivateMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrivateMessage::getSenderId, userId)
                .or()
                .eq(PrivateMessage::getReceiverId, userId)
                .orderByDesc(PrivateMessage::getCreatedAt);
        List<PrivateMessage> allMessages = messageMapper.selectList(wrapper);

        // 按会话ID分组，取每组最后一条
        Map<String, PrivateMessage> lastMessageMap = new LinkedHashMap<>();
        for (PrivateMessage msg : allMessages) {
            if (!lastMessageMap.containsKey(msg.getConversationId())) {
                lastMessageMap.put(msg.getConversationId(), msg);
            }
        }

        // 组装会话列表
        List<Map<String, Object>> conversations = new ArrayList<>();
        for (Map.Entry<String, PrivateMessage> entry : lastMessageMap.entrySet()) {
            PrivateMessage lastMsg = entry.getValue();
            Long otherUserId = lastMsg.getSenderId().equals(userId)
                    ? lastMsg.getReceiverId() : lastMsg.getSenderId();

            // 查询对方用户信息
            User otherUser = userMapper.selectById(otherUserId);
            Long unread = getUnreadCountWithUser(userId, otherUserId);

            Map<String, Object> conv = new HashMap<>();
            conv.put("conversationId", entry.getKey());
            conv.put("otherUserId", otherUserId);
            conv.put("otherUserNickname", otherUser != null ? otherUser.getNickname() : "未知用户");
            conv.put("otherUserAvatar", otherUser != null ? otherUser.getAvatar() : "");
            conv.put("lastMessage", lastMsg.getContent());
            conv.put("lastMessageTime", lastMsg.getCreatedAt());
            conv.put("lastMessageType", lastMsg.getMsgType());
            conv.put("unreadCount", unread);
            conversations.add(conv);
        }

        // 手动分页
        int total = conversations.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageRecords = fromIndex < total
                ? conversations.subList(fromIndex, toIndex) : new ArrayList<>();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        page.setRecords(pageRecords);
        page.setTotal(total);
        page.setPages((int) Math.ceil((double) total / pageSize));

        log.info("用户{}会话列表查询成功，共{}个会话", userId, total);
        return page;
    }

    @Override
    public Long getUnreadCount(Long userId) {
        LambdaQueryWrapper<PrivateMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrivateMessage::getReceiverId, userId)
                .eq(PrivateMessage::getIsRead, 0);
        return messageMapper.selectCount(wrapper);
    }

    @Override
    public Long getUnreadCountWithUser(Long userId, Long otherUserId) {
        LambdaQueryWrapper<PrivateMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrivateMessage::getSenderId, otherUserId)
                .eq(PrivateMessage::getReceiverId, userId)
                .eq(PrivateMessage::getIsRead, 0);
        return messageMapper.selectCount(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsRead(Long userId, Long otherUserId) {
        LambdaQueryWrapper<PrivateMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrivateMessage::getSenderId, otherUserId)
                .eq(PrivateMessage::getReceiverId, userId)
                .eq(PrivateMessage::getIsRead, 0);

        List<PrivateMessage> unreadList = messageMapper.selectList(wrapper);
        if (unreadList.isEmpty()) return;

        for (PrivateMessage msg : unreadList) {
            PrivateMessage update = new PrivateMessage();
            update.setId(msg.getId());
            update.setIsRead(1);
            messageMapper.updateById(update);
        }
        log.debug("用户{}标记与用户{}的{}条消息为已读", userId, otherUserId, unreadList.size());
    }

    @Override
    public String generateConversationId(Long user1, Long user2) {
        if (user1 <= user2) {
            return user1 + "_" + user2;
        } else {
            return user2 + "_" + user1;
        }
    }
}
