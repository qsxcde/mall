package com.geekmall.modules.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.message.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内消息 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
