package com.geekmall.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分层架构约束（ArchUnit）。
 *
 * <p><b>为什么需要这组测试</b>：本项目的分层纪律原本只靠「大家都自觉」维持 ——
 * controller 不直接调 mapper、common 不反向依赖业务域、entity 不依赖 service。
 * 这些约定在代码评审里很难被稳定发现（一个 `import` 混进 PR 里几乎不可见），
 * 一旦破口，后面的人就会跟着破口写。把它们固化成测试后，<b>违反会直接编译期后的测试红灯</b>。</p>
 *
 * <p>与项目已有的 {@code RateLimitCoverageIntegrationTest} 是同一思路：
 * 「不允许遗漏、不允许退化」这件事，只有用测试守住才可信。</p>
 *
 * <p><b>规则分两类</b>：</p>
 * <ul>
 *   <li>{@code 当前成立} —— 现有代码全部满足，加进来是为了<b>防止未来退化</b>；</li>
 *   <li>{@code 已知缺口} —— 现有代码违反，用 {@code @ArchIgnore} 或注释标注，
 *       作为重构待办清单的<b>可执行版本</b>（修完就把规则打开）。</li>
 * </ul>
 *
 * <p>只分析 main 源码，不分析测试代码（测试里大量直接 new 各层对象是正常的）。</p>
 */
@AnalyzeClasses(
        packages = "com.geekmall",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class LayeredArchitectureTest {

    // ==================================================================
    // 一、当前成立 —— 守住现有分层
    // ==================================================================

    /**
     * 控制器不得直接访问 Mapper。
     *
     * <p>控制器只应编排 service；直接碰 mapper 意味着「业务规则写在了 HTTP 层」，
     * 同一规则在其他入口（定时任务、消息消费者）就无法复用，事务边界也会错位。</p>
     */
    @ArchTest
    static final ArchRule controller_不应直接依赖_mapper = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..mapper..")
            .because("控制器应通过 service 访问数据，避免业务规则上浮到 HTTP 层");

    /**
     * 通用治理层不得反向依赖业务域。
     *
     * <p>{@code common} 是被所有业务域依赖的底座（限流、缓存、熔断、日志）。
     * 它一旦 import 了某个业务域，就形成了「底座依赖上层」的倒置 ——
     * 后果是该域无法独立演进，且 {@code common} 的复用性被单个业务绑死。</p>
     */
    @ArchTest
    static final ArchRule common_不应依赖业务域 = noClasses()
            .that()
            .resideInAPackage("com.geekmall.common..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.geekmall.modules..")
            .because("common 是全站底座，反向依赖业务域会造成依赖倒置");

    /**
     * 安全层不得依赖业务域。
     *
     * <p>{@code security} 只负责「解析令牌 → 填充 SecurityContext」，
     * 它被过滤器链调用，处于请求生命周期最早的位置。依赖业务域会让
     * 鉴权逻辑与业务规则纠缠，也会拖慢启动。</p>
     */
    @ArchTest
    static final ArchRule security_不应依赖业务域 = noClasses()
            .that()
            .resideInAPackage("com.geekmall.security..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.geekmall.modules..")
            .because("安全层只做令牌解析与上下文填充，不应感知业务");

    /**
     * 实体不得依赖 service / mapper / controller。
     *
     * <p>entity 是数据载体，被所有层共用。一旦它 import 了 service，
     * 就会形成「数据模型 → 业务逻辑」的依赖，MyBatis 映射与序列化也会出问题。</p>
     */
    @ArchTest
    static final ArchRule entity_不应依赖上层 = noClasses()
            .that()
            .resideInAPackage("..entity..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..service..", "..mapper..", "..controller..")
            .because("实体应是纯数据载体，反向依赖上层会导致循环");

    /**
     * controller 不得依赖 service 的 impl 包。
     *
     * <p>Spring 通过接口注入，控制器看到实现类就意味着「面向实现编程」，
     * 后续替换实现（如换搜索引擎、换支付渠道）会波及控制器。</p>
     */
    @ArchTest
    static final ArchRule controller_不应依赖_service实现 = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..service.impl..")
            .because("控制器应依赖 service 接口而非具体实现");

    // ==================================================================
    // 二、已知缺口 —— 现状违反，作为重构待办
    // ==================================================================

    // ==================================================================
    // 三、跨域依赖 —— 已治理完成，防止回流
    // ==================================================================

    /**
     * 业务域之间不得直接依赖其他域的 Mapper。
     *
     * <p><b>为什么这条规则值得单独写</b>：治理前共 <b>24 处</b>跨域 Mapper 直连
     * （merchant 直连 trade 的 {@code OrderMapper}/{@code OrderItemMapper} 各 4 处；
     * 5 个域各自调用 {@code ProductMapper.deductStock}）。危害不是"多写几行"：</p>
     * <ul>
     *   <li>店铺隔离条件（{@code shop_id = ?}）要每个调用方自己记得加，<b>漏写即越权</b>；</li>
     *   <li>「扣库存 = 条件更新、影响行数为 0 即不足」失去单一所有者，<b>任何一处理解错就是超卖</b>；</li>
     *   <li>被依赖域改表结构会连锁编译失败。</li>
     * </ul>
     *
     * <p><b>为什么不用 {@code slices().notDependOnEachOther()}</b>：域之间的
     * service 级协作是<b>允许且必要</b>的（秒杀→下单、支付→改单、下单向营销域用券），
     * 一刀切会误杀真实业务依赖。因此用编程式 API 精确判定「同域放行、跨域 Mapper 禁止」。</p>
     */
    @ArchTest
    static void 业务域不应依赖其他域的_mapper(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            String fromDomain = domainOf(javaClass.getPackageName());
            if (fromDomain == null) {
                continue;
            }
            for (JavaClass target : javaClass.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .toList()) {
                String toDomain = domainOf(target.getPackageName());
                if (toDomain == null || toDomain.equals(fromDomain)) {
                    continue;
                }
                if (target.getPackageName().contains(".mapper")) {
                    violations.add(javaClass.getName() + " -> " + target.getName());
                }
            }
        }
        assertThat(violations).as("业务域不得直连其他域的 Mapper（应通过对方域提供的 service 访问）").isEmpty();
    }

    /**
     * 域之间不得出现<b>循环</b>依赖。
     *
     * <p>循环依赖本身不致命（Spring 能处理），但它意味着两个域无法独立演进：
     * 改 A 必然波及 B，拆模块/拆服务时也无从下手。</p>
     *
     * <p><b>当前存在 4 处已知循环</b>（均为 service 层的真实业务相互性）：</p>
     * <ul>
     *   <li>{@code marketing ↔ trade} —— 秒杀要下单，下单要用券；</li>
     *   <li>{@code trade ↔ payment} —— 支付要改单，取消订单要退款；</li>
     *   <li>{@code user ↔ marketing} —— 积分消费要扣用户积分；</li>
     *   <li>以及上述组合出的传递环。</li>
     * </ul>
     *
     * <p>彻底消除需要引入领域事件（下单向 marketing 发「订单已创建」、
     * 支付向 trade 发「支付成功」），属较大改造。本测试<b>只记录不阻断</b>：
     * 把当前环数钉住，新增环会让测试失败，从而阻止情况恶化。</p>
     */
    @ArchTest
    static void 域间循环依赖不应增加(JavaClasses classes) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (JavaClass javaClass : classes) {
            String from = domainOf(javaClass.getPackageName());
            if (from == null) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String to = domainOf(dependency.getTargetClass().getPackageName());
                if (to != null && !to.equals(from)) {
                    edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
                }
            }
        }
        int cycles = countCycles(edges);
        // 治理前 5 处 → 拆分 MerchantAftersaleStatus 后 4 处。钉住上限，防止恶化。
        assertThat(cycles).as("域间循环依赖数（当前已知 4 处，若增加说明新引入了双向依赖）").isLessThanOrEqualTo(4);
    }

    /** 从包名解析所属业务域：{@code com.geekmall.modules.trade.xxx} -> {@code trade}。 */
    private static String domainOf(String packageName) {
        String prefix = "com.geekmall.modules.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** 统计有向图中的环数（按「节点集合」去重，同一个环只算一次）。 */
    private static int countCycles(Map<String, Set<String>> edges) {
        Set<Set<String>> found = new HashSet<>();
        for (String start : edges.keySet()) {
            dfs(start, start, edges, new ArrayList<>(), new HashSet<>(), found);
        }
        return found.size();
    }

    private static void dfs(
            String start,
            String current,
            Map<String, Set<String>> edges,
            List<String> path,
            Set<String> visited,
            Set<Set<String>> found) {
        path.add(current);
        for (String next : edges.getOrDefault(current, Set.of())) {
            if (next.equals(start)) {
                found.add(new HashSet<>(path));
            } else if (!visited.contains(next) && !path.contains(next)) {
                visited.add(next);
                dfs(start, next, edges, path, visited, found);
            }
        }
        path.remove(path.size() - 1);
    }
}
