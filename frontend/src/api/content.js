import request from './request'

/** 内容：关于我们 / 帮助中心 / 政策条款 */
export const contentApi = {
  about() {
    return request.get('/cms/about')
  },

  async faqs() {
    const list = await request.get('/cms/faqs')
    return (list || []).map((f) => ({ id: f.id, q: f.q, a: f.a }))
  },

  async policies() {
    const list = await request.get('/cms/policies')
    return (list || []).map((p) => ({ id: p.id, title: p.title, items: p.items || [] }))
  }
}

/** 文件上传（MinIO 或本地磁盘） */
export const fileApi = {
  /**
   * 上传图片
   * @param {File} file
   * @param {'avatar'|'review'|'aftersale'|'common'} biz 业务目录
   */
  upload(file, biz = 'common') {
    const form = new FormData()
    form.append('file', file)
    return request.post('/files', form, {
      params: { biz },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

export default contentApi
