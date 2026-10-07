import request from './request'
import { cleanParams } from './helpers'
import { toCategories, toPage, toProduct, toProducts } from './adapters'

/** 商品 / 分类 / 首页 / 新品 */
export const productApi = {
  async categories() {
    return toCategories(await request.get('/categories/tree'))
  },

  /** 商品列表：cat / sub / keyword / brand / priceMin / priceMax / sort / page / pageSize */
  async list(params = {}) {
    const data = await request.get('/products', { params: cleanParams(params) })
    return toPage(data, toProduct)
  },

  async search(params = {}) {
    const data = await request.get('/products/search', { params: cleanParams(params) })
    return toPage(data, toProduct)
  },

  async detail(id) {
    return toProduct(await request.get(`/products/${id}`))
  },

  async recommend(id, limit = 6) {
    return toProducts(await request.get(`/products/${id}/recommend`, { params: { limit } }))
  },

  /** 新品首发：直接复用列表接口的 isNew 条件，无需单独接口 */
  async newProducts(params = {}) {
    const data = await request.get('/products', {
      params: cleanParams({ isNew: true, pageSize: 12, ...params })
    })
    return toPage(data, toProduct)
  },

  /** 首页楼层：分类 + 热销 + 新品，一次请求渲染整页 */
  async homeFloors() {
    const data = await request.get('/home/floors')
    return {
      categories: toCategories(data.categories),
      hotProducts: toProducts(data.hotProducts),
      newProducts: toProducts(data.newProducts)
    }
  },

  async reviews(productId, params = {}) {
    const data = await request.get(`/products/${productId}/reviews`, { params: cleanParams(params) })
    return toPage(data)
  }
}

export default productApi
