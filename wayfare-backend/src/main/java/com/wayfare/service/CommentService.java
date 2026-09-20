package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.dto.CommentDTO;
import com.wayfare.entity.Comment;

/**
 * 评论服务接口
 */
public interface CommentService {

    /**
     * 创建评论
     */
    Comment create(CommentDTO commentDTO, Long userId);

    /**
     * 删除评论（逻辑删除）
     */
    void delete(Long id, Long userId, boolean isAdmin);

    /**
     * 根据ID查询评论
     */
    Comment getById(Long id);

    /**
     * 分页查询作品的评论列表（一级评论）
     */
    IPage<Comment> pageByWork(Long workId, Integer pageNum, Integer pageSize);

    /**
     * 查询某条评论的回复列表（二级评论）
     */
    IPage<Comment> pageReplies(Long parentId, Integer pageNum, Integer pageSize);

    /**
     * 分页查询用户的评论列表
     */
    IPage<Comment> pageByUser(Long userId, Integer pageNum, Integer pageSize);
}
