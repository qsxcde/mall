package com.geekmall.modules.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.message.entity.MessageRead;
import org.apache.ibatis.annotations.Mapper;

/**
 * 已读回执 Mapper。
 */
@Mapper
public interface MessageReadMapper extends BaseMapper<MessageRead> {
}
