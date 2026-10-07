import { onBeforeUnmount, ref, watch } from 'vue'

/** 三次缓出：起步快、收尾稳，适合数字跳动 */
const easeOut = (t) => 1 - (1 - t) ** 3

/** 是否开启了系统的「减少动态效果」 */
const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/**
 * 数字滚动动画。
 *
 * @param {import('vue').Ref<number>} source 目标值（响应式）
 * @param {object} options duration 时长(ms)、decimals 小数位
 * @returns {{ display: import('vue').Ref<number>, replay: () => void }}
 *
 * 特点：
 * - 用 requestAnimationFrame 而非 CSS，能真实逐帧插值
 * - 自动响应 source 变化重播
 * - 尊重 prefers-reduced-motion，直接跳到终值
 * - 组件卸载时取消动画，避免回调里写已卸载的 ref
 */
export function useCountUp(source, options = {}) {
  const { duration = 900, decimals = 0 } = options

  const display = ref(0)
  let raf = null
  let startedAt = 0
  let from = 0
  let to = 0

  const round = (v) => Number(v.toFixed(decimals))

  function stop() {
    if (raf) {
      cancelAnimationFrame(raf)
      raf = null
    }
  }

  function tick(now) {
    const elapsed = now - startedAt
    const progress = Math.min(1, elapsed / duration)
    display.value = round(from + (to - from) * easeOut(progress))
    if (progress < 1) {
      raf = requestAnimationFrame(tick)
    } else {
      raf = null
      display.value = round(to)
    }
  }

  /** 从当前显示值滚到目标值，避免中途打断时「跳回去」 */
  function start(target) {
    stop()
    to = Number(target) || 0
    from = Number(display.value) || 0
    startedAt = 0

    if (prefersReducedMotion() || duration <= 0 || from === to) {
      display.value = round(to)
      return
    }

    raf = requestAnimationFrame((now) => {
      startedAt = now
      tick(now)
    })
  }

  watch(source, (v) => start(v), { immediate: true })

  onBeforeUnmount(stop)

  return { display, replay: () => start(to) }
}
