package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.CommentDTO;
import com.wayfare.entity.Comment;
import com.wayfare.entity.User;
import com.wayfare.entity.Work;
import com.wayfare.mapper.CommentMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.mapper.WorkMapper;
import com.wayfare.service.CommentService;
import com.wayfare.service.ContentAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);

    private final CommentMapper commentMapper;
    private final WorkMapper workMapper;
    private final UserMapper userMapper;
    private final ContentAuditService contentAuditService;

    public CommentServiceImpl(CommentMapper commentMapper, WorkMapper workMapper,
                              UserMapper userMapper, ContentAuditService contentAuditService) {
        this.commentMapper = commentMapper;
        this.workMapper = workMapper;
        this.userMapper = userMapper;
        this.contentAuditService = contentAuditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Comment create(CommentDTO commentDTO, Long userId) {
        // 内容审核
        contentAuditService.audit(commentDTO.getContent(), "评论内容");

        Work work = workMapper.selectById(commentDTO.getWorkId());
        if (work == null) {
            throw new BusinessException(ResultCode.WORK_NOT_FOUND);
        }

        Long parentId = commentDTO.getParentId();
        if (parentId != null && parentId > 0) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
            }
            if (parent.getParentId() != null && parent.getParentId() > 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不支持三级回复");
            }
        } else {
            parentId = 0L;
        }

        Comment comment = new Comment();
        comment.setWorkId(commentDTO.getWorkId());
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(commentDTO.getReplyToUserId() != null ? commentDTO.getReplyToUserId() : 0L);
        comment.setContent(commentDTO.getContent());
        comment.setLikeCount(0);
        comment.setStatus(1);
        commentMapper.insert(comment);

        Work workUpdate = new Work();
        workUpdate.setId(work.getId());
        workUpdate.setCommentCount(work.getCommentCount() + 1);
        workMapper.updateById(workUpdate);

        log.info("评论创建成功: commentId={}, workId={}, userId={}", comment.getId(), comment.getWorkId(), userId);
        return comment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId, boolean isAdmin) {
        Comment comment = getById(id);
        if (!comment.getUserId().equals(userId) && !isAdmin) {
            throw new BusinessException(ResultCode.COMMENT_NO_PERMISSION);
        }

        Comment update = new Comment();
        update.setId(id);
        update.setStatus(0);
        commentMapper.updateById(update);

        Work work = workMapper.selectById(comment.getWorkId());
        if (work != null && work.getCommentCount() > 0) {
            Work workUpdate = new Work();
            workUpdate.setId(work.getId());
            workUpdate.setCommentCount(work.getCommentCount() - 1);
            workMapper.updateById(workUpdate);
        }

        log.info("评论删除成功: commentId={}, userId={}", id, userId);
    }

    @Override
    public Comment getById(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null || comment.getStatus() == 0) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }

    @Override
    public IPage<Comment> pageByWork(Long workId, Integer pageNum, Integer pageSize) {
        Page<Comment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getWorkId, workId)
                .eq(Comment::getParentId, 0)
                .eq(Comment::getStatus, 1)
                .orderByDesc(Comment::getCreatedAt);
        IPage<Comment> result = commentMapper.selectPage(page, wrapper);
        // 填充用户信息和回复
        for (Comment comment : result.getRecords()) {
            fillCommentInfo(comment);
            // 查询回复（最多显示5条）
            LambdaQueryWrapper<Comment> replyWrapper = new LambdaQueryWrapper<>();
            replyWrapper.eq(Comment::getParentId, comment.getId())
                    .eq(Comment::getStatus, 1)
                    .orderByAsc(Comment::getCreatedAt)
                    .last("LIMIT 5");
            List<Comment> replies = commentMapper.selectList(replyWrapper);
            for (Comment reply : replies) {
                fillCommentInfo(reply);
            }
            comment.setReplies(replies);
        }
        return result;
    }

    /**
     * 填充评论的用户信息和被回复者信息
     */
    private void fillCommentInfo(Comment comment) {
        // 填充评论者信息
        if (comment.getUserId() != null) {
            User user = userMapper.selectById(comment.getUserId());
            if (user != null) {
                user.setPassword(null);
                comment.setUser(user);
            }
        }
        // 填充被回复者信息
        if (comment.getReplyToUserId() != null && comment.getReplyToUserId() > 0) {
            User replyToUser = userMapper.selectById(comment.getReplyToUserId());
            if (replyToUser != null) {
                replyToUser.setPassword(null);
                comment.setReplyToUser(replyToUser);
            }
        }
    }

    @Override
    public IPage<Comment> pageReplies(Long parentId, Integer pageNum, Integer pageSize) {
        Page<Comment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getParentId, parentId)
                .eq(Comment::getStatus, 1)
                .orderByAsc(Comment::getCreatedAt);
        IPage<Comment> result = commentMapper.selectPage(page, wrapper);
        // 填充用户信息
        for (Comment comment : result.getRecords()) {
            fillCommentInfo(comment);
        }
        return result;
    }

    @Override
    public IPage<Comment> pageByUser(Long userId, Integer pageNum, Integer pageSize) {
        Page<Comment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getUserId, userId)
                .eq(Comment::getStatus, 1)
                .orderByDesc(Comment::getCreatedAt);
        return commentMapper.selectPage(page, wrapper);
    }
}
