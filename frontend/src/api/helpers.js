/** 去掉空值参数，避免后端收到 `keyword=` 这类无意义条件 */
export function cleanParams(params = {}) {
  const result = {}
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') return
    result[key] = value
  })
  return result
}
