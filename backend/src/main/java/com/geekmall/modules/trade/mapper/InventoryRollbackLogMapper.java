package com.geekmall.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.trade.entity.InventoryRollbackLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 库存回退幂等日志 Mapper。
 */
@Mapper
public interface InventoryRollbackLogMapper extends BaseMapper<InventoryRollbackLog> {

    /**
     * 尝试登记一次库存回退。
     *
     * @return 1 表示首次登记（允许回退）；0 表示该 (orderNo, productId) 已登记过（跳过回退）
     */
    @Insert("INSERT IGNORE INTO inventory_rollback_log(order_no, product_id, qty) "
            + "VALUES(#{orderNo}, #{productId}, #{qty})")
    int tryInsert(@Param("orderNo") String orderNo,
                  @Param("productId") Long productId,
                  @Param("qty") int qty);
}
