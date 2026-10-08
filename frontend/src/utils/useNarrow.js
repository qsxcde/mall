import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 窄屏判定（默认 ≤768px）。
 *
 * 用于 Element Plus 中那些「列数写死」的组件（el-descriptions / el-col 等）：
 * 它们不接受 CSS 媒体查询来改列数，只能在 JS 侧把列数降为 1，
 * 否则 375px 宽的屏幕上会被挤成两列、内容无法阅读。
 */
export function useNarrow(breakpoint = 768) {
  const narrow = ref(typeof window !== 'undefined' && window.innerWidth <= breakpoint)

  const update = () => {
    narrow.value = window.innerWidth <= breakpoint
  }

  onMounted(() => {
    update()
    window.addEventListener('resize', update)
  })
  onBeforeUnmount(() => window.removeEventListener('resize', update))

  return narrow
}

export default useNarrow
