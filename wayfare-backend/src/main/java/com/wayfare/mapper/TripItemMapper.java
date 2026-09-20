package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.TripItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行程条目Mapper接口
 */
@Mapper
public interface TripItemMapper extends BaseMapper<TripItem> {
}
