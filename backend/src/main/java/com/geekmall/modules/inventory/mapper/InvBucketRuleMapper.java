package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucketRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分桶规则 Mapper。
 */
@Mapper
public interface InvBucketRuleMapper extends BaseMapper<InvBucketRule> {
}
