/**
 * 内容域：关于我们、帮助中心 FAQ、政策条款、文件上传。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code ContentService}：about（统计数字实时取自各域 service，非硬编码）、faqs、policies</li>
 *     <li>{@code FileService}：基于 <b>MinIO</b> 的图片上传，用于头像 / 评价图 / 售后凭证</li>
 *     <li>{@code ContentController} → /api/v1/cms/**（白名单）；{@code FileController} → /api/v1/files</li>
 * </ul>
 *
 * <h3>文件上传的几个要点</h3>
 * <ol>
 *     <li><b>不信任原始文件名</b>：扩展名按 content-type 推导，对象名用
 *         {@code {biz}/{yyyy}/{MM}/{dd}/{uuid}.{ext}}，天然按天归档且不会重名/路径穿越。</li>
 *     <li><b>不在启动时探测对象存储</b>：MinIO 不可用不应该拖垮应用启动，
 *         因此建桶与策略设置延迟到首次上传（双检锁只做一次）。</li>
 *     <li><b>白名单校验</b>：限制 content-type 与 10MB 大小；biz 也做枚举校验，防止乱建目录。</li>
 *     <li><b>URL 可配置</b>：{@code mall.minio.public-base-url} 可指向 Nginx / CDN，
 *         便于生产改用预签名 URL 或私有桶。</li>
 * </ol>
 *
 * <p>待实现：CMS 后台（Faq/Policy 的增删改）、富文本正文、预览图压缩。</p>
 *
 * <p>对应前端：AboutView、HelpView、PolicyView，以及头像 / 评价图 / 售后凭证上传。</p>
 */
package com.geekmall.modules.content;
