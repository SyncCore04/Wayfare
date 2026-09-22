package com.wayfare.connector.governance;

import java.util.Map;

/**
 * 外部调用日志写入接口。
 *
 * <p>抽成接口而不是直接依赖 Mapper，有两个实在的理由：
 * <ol>
 *   <li><b>可测</b>：P1-D 的重试单测要能「数出写了几条日志」，
 *       用接口注入一个记账实现即可，不必起 Spring 上下文与数据库；</li>
 *   <li><b>可降级</b>：将来日志量大了要改成异步入队，换实现即可，治理层不用动。</li>
 * </ol>
 */
public interface ExternalCallLogService {

    /**
     * 记一条调用日志。
     *
     * <p><b>实现方必须吞掉异常</b>：记日志失败绝不能影响主流程
     * （一个日志表的问题不该让用户的行程规划失败）。
     */
    void record(ExternalCallRecord record);

    /**
     * 某连接器的运行统计（诊断接口 P1-E 用）。
     *
     * @return lastSuccessAt / lastErrorAt / lastErrorMessage / failRate1h / calls1h / failures1h / todayCallCount。
     *         字段可能为 null（比如一次调用都还没有）。{@code lastErrorMessage} 于 P6-A 补充 ——
     *         状态卡片要能直接告诉管理员「上次为什么失败」，而不是让他再去翻日志表。
     */
    Map<String, Object> stats(String connector);

    /**
     * 外呼日志分页（P6-B · 看板「外部调用」页签）。
     *
     * <p><b>只读，没有删除入口</b>：日志是证据链，可删的日志等于没有日志
     * （手册 P6-B 明确要求「不要把 AI 生成日志做成可删除的」）。
     *
     * @param connector 连接器类型，传 null 表示全部
     * @param page      页码，从 1 起
     * @param size      每页条数（调用方需自行限幅）
     * @return 形如 {@code {total, page, size, records:[...]}}；
     *         查询失败时返回 {@code {error}} 而不是抛异常 —— 看板的一个页签挂掉
     *         不该让整个页面变成 500。
     */
    Map<String, Object> page(String connector, int page, int size);
}
