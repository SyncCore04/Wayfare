package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.Trip;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行程主表Mapper接口
 */
@Mapper
public interface TripMapper extends BaseMapper<Trip> {
}
