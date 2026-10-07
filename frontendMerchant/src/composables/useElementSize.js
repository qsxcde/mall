import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 观测元素尺寸。
 *
 * 图表为什么不用 `viewBox + preserveAspectRatio="none"` 自适应？
 * 因为非等比缩放会把描边拉粗、把圆点压成椭圆、把文字挤变形。
 * 这里改为「真实测量容器 → 用像素坐标绘制」，配合 vector-effect 也省了，
 * 缩放窗口时图表是干净重绘而不是被拉伸。
 */
export function useElementSize(target) {
  const width = ref(0)
  const height = ref(0)
  let observer = null

  function measure(el) {
    const rect = el.getBoundingClientRect()
    width.value = Math.round(rect.width)
    height.value = Math.round(rect.height)
  }

  onMounted(() => {
    const el = target.value
    if (!el) return
    measure(el)
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect
      if (!rect) return
      width.value = Math.round(rect.width)
      height.value = Math.round(rect.height)
    })
    observer.observe(el)
  })

  onBeforeUnmount(() => observer?.disconnect())

  return { width, height }
}
