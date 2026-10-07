package com.geekmall.modules.content.service;

import com.geekmall.modules.content.vo.AboutStatVO;
import com.geekmall.modules.content.vo.FaqVO;
import com.geekmall.modules.content.vo.PolicyVO;

import java.util.List;

/**
 * 内容服务：关于我们、帮助中心、政策条款。
 */
public interface ContentService {

    /** 站点统计（数值实时取自数据库，不是硬编码）。 */
    List<AboutStatVO> about();

    /** 帮助中心 FAQ。 */
    List<FaqVO> faqs();

    /** 政策条款。 */
    List<PolicyVO> policies();
}
