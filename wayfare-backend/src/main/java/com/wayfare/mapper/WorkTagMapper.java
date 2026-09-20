package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.WorkTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 作品-标签关联Mapper接口
 */
@Mapper
public interface WorkTagMapper extends BaseMapper<WorkTag> {
}
