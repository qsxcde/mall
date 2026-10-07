package com.geekmall.common.ratelimit;

/**
 * 限流执行层级：决定计数放在进程内还是 Redis。
 *
 * <p>这直接决定限流的成本与语义，是本模块最重要的一次取舍：</p>
 * <table border="1">
 *   <caption>层级对比</caption>
 *   <tr><th>层级</th><th>成本</th><th>一致性</th><th>适用</th></tr>
 *   <tr>
 *     <td>{@link #LOCAL}</td>
 *     <td>纯内存，零网络往返</td>
 *     <td>单实例内准确；多实例时总阈值 = 单实例阈值 × 实例数</td>
 *     <td>高频读接口、以及「已有业务幂等兜底」的写接口</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #DISTRIBUTED}</td>
 *     <td>每次请求一次 Redis 往返</td>
 *     <td>跨实例全局准确</td>
 *     <td>限流本身就是唯一防线、且必须全局准确的接口（防撞库 / 防短信轰炸 / 防爆破）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么默认不用 DISTRIBUTED</b>：早前把拦截器统一挂 Redis INCR，
 * 压力测试实测把普通下单 P99 抬高了 12.1 倍 —— 限流器自己成了性能瓶颈。
 * 因此本模块只在「漏了会有真实代价」的少数接口上付这个成本；
 * 下单、领券、兑换这类接口的防重由唯一索引与幂等键兜底，限流只需要挡住「手抖与脚本」，
 * 用本地计数即可。</p>
 */
public enum RateLimitTier {

    /** 进程内计数（滑动窗口）。 */
    LOCAL,

    /** Redis 全局计数（固定窗口，Lua 保证 INCR + EXPIRE 原子）。 */
    DISTRIBUTED
}
