package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucketOperation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调拨 / 合并操作单 Mapper。
 */
@Mapper
public interface InvBucketOperationMapper extends BaseMapper<InvBucketOperation> {
}
