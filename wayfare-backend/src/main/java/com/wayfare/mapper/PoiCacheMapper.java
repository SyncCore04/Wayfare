package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.PoiCache;
import org.apache.ibatis.annotations.Mapper;

/**
 * POI 缓存Mapper接口
 *
 * <p>供 {@code LocalCacheMapProvider} 在熔断/关闭地图时读取历史 POI 数据。
 */
@Mapper
public interface PoiCacheMapper extends BaseMapper<PoiCache> {
}
