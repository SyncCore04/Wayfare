package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.Work;
import org.apache.ibatis.annotations.Mapper;

/**
 * 作品Mapper接口
 */
@Mapper
public interface WorkMapper extends BaseMapper<Work> {
}
