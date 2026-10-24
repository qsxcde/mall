package com.geekmall.modules.marketing.dto;

import java.util.List;

/**
 * 批量落库结果。
 *
 * <p>只包含<b>业务结果</b>；系统异常以抛出异常表达，由消费端回退到逐条路径。</p>
 */
public record BatchPersistResult(List<BatchOrderOutcome> outcomes) {}
