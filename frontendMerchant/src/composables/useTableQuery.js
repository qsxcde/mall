import { computed, reactive, ref, watch } from 'vue'

/**
 * 列表页通用查询逻辑。
 *
 * 把「筛选 → 请求 → 分页 → 选中」这套每个列表页都要写一遍的东西收口到一处，
 * 视图里只剩下「声明参数 + 渲染表格」。
 *
 * @param {Function} fetcher 形如 (params) => Promise<{ list, total, ...rest }>
 * @param {object} options
 *   - defaultParams: 初始筛选条件
 *   - watchKeys:     哪些字段变化时自动重新查询（会自动回到第 1 页）
 *   - size:          每页条数
 *   - immediate:     是否挂载即查询
 *   - rowKey:        行主键字段名，用于选中集
 */
export function useTableQuery(fetcher, options = {}) {
  const {
    defaultParams = {},
    watchKeys = [],
    size = 8,
    immediate = true,
    rowKey = 'id'
  } = options

  /** 查询参数：page/size 与筛选条件放在一起，直接透传给接口 */
  const params = reactive({ page: 1, size, ...defaultParams })
  const list = ref([])
  const total = ref(0)
  const loading = ref(false)
  /** 接口随列表一起返回的附加数据（Tab 计数、统计条等） */
  const extras = ref({})
  /** 已选中的行主键集合 */
  const selected = ref([])

  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / params.size)))
  const isEmpty = computed(() => !loading.value && list.value.length === 0)
  const selectedCount = computed(() => selected.value.length)

  /** 是否处于「有筛选条件」的状态，用于给空态切换文案 */
  const isFiltered = computed(() =>
    Object.entries(defaultParams).some(([k, v]) => params[k] !== v && k !== 'page' && k !== 'size')
  )

  let seq = 0

  async function load() {
    const current = ++seq
    loading.value = true
    try {
      const res = (await fetcher({ ...params })) || {}
      // 竞态保护：只接受最后一次请求的结果，避免快速切 Tab 时旧响应覆盖新数据
      if (current !== seq) return res
      list.value = res.list || []
      total.value = res.total || 0
      const { list: _list, total: _total, page: _page, size: _size, ...rest } = res
      extras.value = rest
      return res
    } finally {
      if (current === seq) loading.value = false
    }
  }

  /** 重新查询并回到第 1 页（筛选条件变化时用） */
  function search() {
    params.page = 1
    selected.value = []
    return load()
  }

  /** 仅换页，保留筛选条件与已选 */
  function changePage(page) {
    params.page = page
    return load()
  }

  /** 重置为初始筛选条件 */
  function reset(overrides = {}) {
    Object.assign(params, { page: 1, size }, defaultParams, overrides)
    selected.value = []
    return load()
  }

  /** 修改某个筛选字段（自动触发重新查询） */
  function setParam(key, value) {
    params[key] = value
  }

  /* ---------- 选中 ---------- */
  const keyOf = (row) => (typeof row === 'string' ? row : row?.[rowKey])

  function toggleSelect(row) {
    const key = keyOf(row)
    const idx = selected.value.indexOf(key)
    if (idx >= 0) selected.value.splice(idx, 1)
    else selected.value.push(key)
  }

  function isSelected(row) {
    return selected.value.includes(keyOf(row))
  }

  function clearSelection() {
    selected.value = []
  }

  /** 全选/取消全选「当前页」 */
  function toggleSelectAll(checked) {
    selected.value = checked ? list.value.map(keyOf) : []
  }

  const allSelected = computed(
    () => list.value.length > 0 && list.value.every((row) => selected.value.includes(keyOf(row)))
  )
  /** 半选态：选了但没选满 */
  const indeterminate = computed(
    () => selected.value.length > 0 && !allSelected.value
  )

  /* ---------- 自动查询 ---------- */
  if (watchKeys.length) {
    watch(
      () => watchKeys.map((k) => params[k]).join('\u0001'),
      () => search()
    )
  }

  if (immediate) load()

  return {
    params,
    list,
    total,
    loading,
    extras,
    selected,
    selectedCount,
    pageCount,
    isEmpty,
    isFiltered,
    allSelected,
    indeterminate,
    load,
    search,
    changePage,
    reset,
    setParam,
    toggleSelect,
    isSelected,
    clearSelection,
    toggleSelectAll
  }
}
