<script setup>
import { computed } from 'vue'
import { AVATAR_TONES } from '@/utils/dict'

/**
 * 买家头像占位：取姓名首字 + 轮换渐变底色。
 * 同一买家在所有页面颜色一致（按索引取模），便于跨页面辨认。
 */
const props = defineProps({
  name: { type: String, default: '' },
  index: { type: Number, default: 0 },
  size: { type: Number, default: 34 }
})

const initial = computed(() => props.name.slice(0, 1) || '?')
const tone = computed(() => AVATAR_TONES[props.index % AVATAR_TONES.length])
</script>

<template>
  <div
    class="avatar"
    :class="`tone-${tone}`"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.38)}px` }"
  >
    {{ initial }}
  </div>
</template>

<style scoped>
.avatar {
  flex-shrink: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
}
.tone-brand {
  background: linear-gradient(135deg, #4da3ff, var(--brand));
}
.tone-coral {
  background: linear-gradient(135deg, #ffb37a, #ff7a45);
}
.tone-teal {
  background: linear-gradient(135deg, #7fd6bf, #0d9488);
}
.tone-gold {
  background: linear-gradient(135deg, #ffd88a, var(--gold));
}
.tone-violet {
  background: linear-gradient(135deg, #b6a2ff, #7c5cff);
}
</style>
