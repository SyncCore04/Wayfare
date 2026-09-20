package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.AdminOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 后台操作日志Mapper接口
 *
 * <p>P0-B 要求这张表真实写入：P0-C 起的后台管理动作都要经它落一条审计记录。
 */
@Mapper
public interface AdminOperationLogMapper extends BaseMapper<AdminOperationLog> {
}
