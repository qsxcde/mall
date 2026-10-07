package com.geekmall.modules.aftersale.service;

import com.geekmall.modules.aftersale.dto.ApplyAfterSaleDTO;
import com.geekmall.modules.aftersale.vo.AfterSaleVO;

import java.util.List;

/**
 * 售后服务。
 */
public interface AfterSaleService {

    /**
     * 申请售后。
     *
     * @return 售后单 ID（前端路由 /aftersale/:id 使用）
     */
    Long apply(Long userId, ApplyAfterSaleDTO dto);

    /** 我的售后列表，status 为 null 时返回全部。 */
    List<AfterSaleVO> list(Long userId, Integer status);

    /** 售后详情（含处理进度时间轴）。 */
    AfterSaleVO detail(Long userId, Long id);

    /** 取消申请（仅处理中可取消）。 */
    void cancel(Long userId, Long id);
}
