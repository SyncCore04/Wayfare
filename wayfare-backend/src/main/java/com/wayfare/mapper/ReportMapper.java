package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.Report;
import org.apache.ibatis.annotations.Mapper;

/**
 * 举报Mapper接口
 *
 * <p>本版按 P0-B 要求仅为预留，不写举报接口逻辑。
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
