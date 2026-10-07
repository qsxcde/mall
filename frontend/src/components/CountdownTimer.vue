<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  hours: { type: Number, default: 0 },
  minutes: { type: Number, default: 0 },
  seconds: { type: Number, default: 0 },
  loop: { type: Boolean, default: true },
  big: { type: Boolean, default: false }
})

const total = ref(props.hours * 3600 + props.minutes * 60 + props.seconds)
let timer = null

const pad = (n) => String(n).padStart(2, '0')
const h = computed(() => pad(Math.floor(total.value / 3600)))
const m = computed(() => pad(Math.floor((total.value % 3600) / 60)))
const s = computed(() => pad(total.value % 60))

const reset = () => {
  total.value = props.hours * 3600 + props.minutes * 60 + props.seconds
}

timer = setInterval(() => {
  if (total.value <= 0) {
    if (props.loop) reset()
    else return
  } else {
    total.value--
  }
}, 1000)

watch(
  () => [props.hours, props.minutes, props.seconds],
  reset
)

onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div :class="big ? 'countdown-big' : 'countdown'">
    <b>{{ h }}</b><span class="colon">:</span><b>{{ m }}</b><span class="colon">:</span><b>{{ s }}</b>
  </div>
</template>
