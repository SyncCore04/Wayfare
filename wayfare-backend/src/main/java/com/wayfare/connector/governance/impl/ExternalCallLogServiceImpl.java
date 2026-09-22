package com.wayfare.connector.governance.impl;

import com.wayfare.connector.governance.ExternalCallLogService;
import com.wayfare.connector.governance.ExternalCallRecord;
import com.wayfare.entity.ExternalCallLog;
import com.wayfare.mapper.ExternalCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部调用日志落库实现。
 *
 * <p><b>整段实现是「尽力而为」的</b>：任何异常都吞掉并记 WARN。
 * 理由很直接 —— 记日志是旁路动作，它失败不该让用户的行程规划失败。
 * 但也因此必须打日志，否则「日志表一直是空的」会没人发现。
 */
@Service
public class ExternalCallLogServiceImpl implements ExternalCallLogService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallLogServiceImpl.class);

    private final ExternalCallLogMapper mapper;

    public ExternalCallLogServiceImpl(ExternalCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(ExternalCallRecord record) {
        if (record == null) return;
        try {
            ExternalCallLog entity = new ExternalCallLog();
            entity.setUserId(record.userId());
            entity.setTripId(record.tripId());
            entity.setConnector(record.connector());
            entity.setApiName(truncate(record.apiName(), 100));
            // 再次保险：即便上游漏了脱敏，这里也只存截断后的内容。
            // 但注意真正的脱敏必须在调用方做（这里没有原值可比对，脱不了）。
            entity.setRequestSummary(truncate(record.requestSummary(), 600));
            entity.setHttpStatus(record.httpStatus());
            entity.setDurationMs(record.durationMs());
            entity.setSuccess(record.success());
            entity.setErrorMsg(truncate(record.errorMsg(), 500));
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("写外部调用日志失败（不影响主流程）: connector={}, api={}, err={}",
                    record.connector(), record.apiName(), e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ==================== 统计（P1-E 诊断接口） ====================

    @Override
    public Map<String, Object> stats(String connector) {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime oneHourAgo = now.minusHours(1);

            java.time.LocalDateTime lastSuccess = mapper.findLastSuccessAt(connector);
            java.time.LocalDateTime lastError = mapper.findLastErrorAt(connector);
            long calls1h = mapper.countSince(connector, oneHourAgo);
            long failures1h = mapper.countFailuresSince(connector, oneHourAgo);

            stats.put("lastSuccessAt", lastSuccess);
            stats.put("lastErrorAt", lastError);
            // 上次失败的原因（P6-A 加的）。截到 200 字：状态卡片上放不下整段堆栈，
            // 而外部服务返回的长报文对「我该做什么」没有额外信息。
            stats.put("lastErrorMessage", truncate(mapper.findLastErrorMessage(connector), 200));
            stats.put("calls1h", calls1h);
            stats.put("failures1h", failures1h);
            // 失败率：窗口内没有调用时给 null（0% 会让「还没发生过」和「都很顺利」看起来一样）
            stats.put("failRate1h", calls1h > 0 ? (double) failures1h / calls1h : null);
            stats.put("todayCallCount", mapper.countToday(connector));
        } catch (Exception e) {
            // 统计挂了不该让诊断接口 500：如实说明拿不到
            stats.put("error", "统计查询失败：" + e.getMessage());
        }
        return stats;
    }
}
