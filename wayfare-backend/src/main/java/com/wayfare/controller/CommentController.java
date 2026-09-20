package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.dto.CommentDTO;
import com.wayfare.entity.Comment;
import com.wayfare.security.UserContext;
import com.wayfare.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    public Result<Comment> create(@Valid @RequestBody CommentDTO commentDTO) {
        Long userId = UserContext.getUserId();
        return Result.success(commentService.create(commentDTO, userId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        boolean isAdmin = UserContext.isAdmin();
        commentService.delete(id, userId, isAdmin);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Comment> getById(@PathVariable Long id) {
        return Result.success(commentService.getById(id));
    }

    @GetMapping("/work/{workId}")
    public Result<IPage<Comment>> pageByWork(
            @PathVariable Long workId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(commentService.pageByWork(workId, pageNum, pageSize));
    }

    @GetMapping("/{id}/replies")
    public Result<IPage<Comment>> pageReplies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(commentService.pageReplies(id, pageNum, pageSize));
    }

    @GetMapping("/my")
    public Result<IPage<Comment>> myComments(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(commentService.pageByUser(userId, pageNum, pageSize));
    }
}
