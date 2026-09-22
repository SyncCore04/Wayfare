package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.ExternalCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 外部调用日志Mapper接口
 *
 * <p>由出站治理层（GovernedExternalHttpClient）写入，P4-C / P6-B / P7-B 读取统计。
 * 这几个统计查询是 P1-E 诊断接口的数据来源。
 */
@Mapper
public interface ExternalCallLogMapper extends BaseMapper<ExternalCallLog> {

    /** 最近一次成功调用时间 */
    @Select("SELECT MAX(created_at) FROM external_call_log WHERE connector = #{connector} AND success = 1")
    LocalDateTime findLastSuccessAt(@Param("connector") String connector);

    /** 最近一次失败调用时间 */
    @Select("SELECT MAX(created_at) FROM external_call_log WHERE connector = #{connector} AND success = 0")
    LocalDateTime findLastErrorAt(@Param("connector") String connector);

    /** 窗口期内的调用总数 */
    @Select("SELECT COUNT(*) FROM external_call_log WHERE connector = #{connector} AND created_at >= #{since}")
    long countSince(@Param("connector") String connector, @Param("since") LocalDateTime since);

    /** 窗口期内的失败次数 */
    @Select("SELECT COUNT(*) FROM external_call_log WHERE connector = #{connector} AND success = 0 AND created_at >= #{since}")
    long countFailuresSince(@Param("connector") String connector, @Param("since") LocalDateTime since);

    /** 今天的调用总数 */
    @Select("SELECT COUNT(*) FROM external_call_log WHERE connector = #{connector} AND created_at >= CURDATE()")
    long countToday(@Param("connector") String connector);

    /**
     * 最近一次失败的原因（P6-A 连接器管理页的状态卡片用）。
     *
     * <p>为什么值得单独查一条：诊断页只报「上次失败时间」时，管理员还得自己去翻日志才知道
     * 失败是 Key 错、超时还是限流。带上原因，页面上一眼就能看出该做什么。
     * 只取最近一条非空 error_msg —— 一条没留原因的失败不该把有原因的那条挤掉。
     */
    @Select("SELECT error_msg FROM external_call_log "
            + "WHERE connector = #{connector} AND success = 0 AND error_msg IS NOT NULL "
            + "ORDER BY created_at DESC LIMIT 1")
    String findLastErrorMessage(@Param("connector") String connector);
}
