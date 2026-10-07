<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import BarChart from '@/components/charts/BarChart.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import { fetchReviewPage, fetchReviewSummary, ignoreReviews, replyReview } from '@/api/growth'
import { useMerchantStore } from '@/stores/merchant'
import { REVIEW_STATUS, REVIEW_TABS, pick } from '@/utils/dict'
import { fmtDate, int, percent } from '@/utils/format'

/**
 * 评价管理。
 * 差评单独成 Tab 并且列表里优先展示 —— 差评的响应速度直接决定 DSR 与转化。
 */
const store = useMerchantStore()

const {
  params,
  list,
  total,
  loading,
  extras,
  pageCount,
  isFiltered,
  load,
  changePage,
  reset
} = useTableQuery(fetchReviewPage, {
  defaultParams: { tab: 'all', keyword: '', sort: 'time_desc' },
  watchKeys: ['tab', 'keyword', 'sort'],
  size: 8
})

watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

/* ---------- DSR 与标签 ---------- */
const summary = ref({ dsr: [], tags: [] })
onMounted(async () => {
  summary.value = await fetchReviewSummary()
})

const sortOptions = [
  { value: 'time_desc', label: '评价时间 · 新→旧' },
  { value: 'time_asc', label: '评价时间 · 旧→新' },
  { value: 'rating_asc', label: '评分 · 低→高' },
  { value: 'rating_desc', label: '评分 · 高→低' }
]

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const s = extras.value.stats || {}
  return [
    { key: 'all', label: '累计评价', value: s.total || 0, suffix: '条', tone: 'brand', desc: '含全部星级' },
    {
      key: 'wait',
      label: '待回复',
      value: s.wait || 0,
      suffix: '条',
      tone: 'amber',
      alert: (s.wait || 0) > 0,
      desc: '及时回复可提升 DSR'
    },
    {
      key: 'good',
      label: '好评率',
      value: (s.goodRate || 0) * 100,
      suffix: '%',
      digits: 1,
      tone: 'green',
      desc: '4~5 星占比'
    },
    {
      key: 'avg',
      label: '平均评分',
      value: s.avg || 0,
      suffix: '分',
      digits: 2,
      tone: 'violet',
      desc: '满分 5.00'
    },
    { key: 'bad', label: '差评数', value: s.badCount || 0, suffix: '条', tone: 'coral', desc: '需优先处理' }
  ]
})

/** 星级分布：把 5/4/3/2/1 星转成柱状图数据，低星用暖色以便一眼识别 */
const distItems = computed(() =>
  (extras.value.stats?.distribution || []).map((d) => ({
    label: `${d.star} 星`,
    value: d.count,
    tone: d.star >= 4 ? 'green' : d.star === 3 ? 'amber' : 'coral'
  }))
)

const tagMax = computed(() => Math.max(...summary.value.tags.map((t) => t.count), 1))

/* ---------- 回复 ---------- */
const replyDialog = reactive({ visible: false, id: '', content: '', buyer: '' })

/** 常用回复话术：好评、差评各一套，减少重复输入 */
const QUICK_REPLY = {
  good: [
    '感谢您的支持！我们会继续保持品质与服务，期待您的再次光临。',
    '非常感谢您的认可，后续有任何使用问题都可以随时联系在线客服。',
    '谢谢亲的信任与支持，正品保障，欢迎下次再来～'
  ],
  bad: [
    '非常抱歉给您带来不好的体验，我们已安排专属客服与您联系，请留意站内信。',
    '抱歉让您失望了。质量问题我们支持 7 天无理由退换，可随时申请售后处理。',
    '给您带来不便深表歉意，已为您加急处理，感谢您的反馈帮助我们改进。'
  ]
}

const quickList = computed(() =>
  list.value.find((r) => r.id === replyDialog.id)?.isBad ? QUICK_REPLY.bad : QUICK_REPLY.good
)

function openReply(row) {
  replyDialog.id = row.id
  replyDialog.buyer = row.buyer.name
  replyDialog.content = row.reply || ''
  replyDialog.visible = true
}

async function confirmReply() {
  if (!replyDialog.content.trim()) {
    ElMessage.warning('请输入回复内容')
    return
  }
  await replyReview(replyDialog.id, replyDialog.content.trim())
  replyDialog.visible = false
  ElMessage.success('回复已发布，买家将收到通知')
  await Promise.all([load(), store.loadBadges(true)])
}

async function ignoreOne(row) {
  try {
    await ElMessageBox.confirm(`忽略后「${row.buyer.name}」的评价将不再出现在待回复列表。`, '忽略评价', {
      type: 'warning',
      confirmButtonText: '确认忽略',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await ignoreReviews([row.id])
  ElMessage.success('已忽略该评价')
  await Promise.all([load(), store.loadBadges(true)])
}

async function bulkReply() {
  const waits = list.value.filter((r) => r.status === 'wait')
  if (!waits.length) {
    ElMessage.warning('当前页没有待回复的评价')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将为当前页 ${waits.length} 条待回复评价发送统一话术回复。`,
      '批量回复',
      { type: 'warning', confirmButtonText: '确认回复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  ElMessage.success(`已批量回复 ${waits.length} 条评价`)
  await Promise.all([load(), store.loadBadges(true)])
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Growth · Review Center" title="评价" title-accent="管理">
      <template #desc>
        平均评分 <b>{{ (extras.stats?.avg || 0).toFixed(2) }}</b> ·
        好评率 <b>{{ percent(extras.stats?.goodRate || 0) }}</b> ·
        待回复 <b>{{ extras.stats?.wait || 0 }}</b> 条
      </template>
      <template #actions>
        <el-button @click="ElMessage.success('评价设置已打开（演示）')">
          <el-icon><Setting /></el-icon> 评价设置
        </el-button>
        <el-button type="primary" @click="bulkReply">
          <el-icon><ChatLineSquare /></el-icon> 批量回复
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <!-- ============ DSR + 星级分布 + 标签 ============ -->
    <div class="row row--3">
      <section class="mz-card">
        <div class="mz-card__head">
          <h3>店铺 DSR 评分</h3>
          <span class="sub">近 90 天</span>
        </div>
        <div class="dsr">
          <div v-for="d in summary.dsr" :key="d.key" class="dsr__item">
            <div class="dsr__top">
              <span class="dsr__label">{{ d.label }}</span>
              <b class="num">{{ d.score.toFixed(2) }}</b>
            </div>
            <div class="dsr__bar">
              <i :style="{ width: `${(d.score / 5) * 100}%` }" />
              <!-- 行业均值刻度线，让「高不高」有参照 -->
              <em :style="{ left: `${(d.industry / 5) * 100}%` }" />
            </div>
            <div class="dsr__foot">
              <span>行业均值 {{ d.industry.toFixed(2) }}</span>
              <span class="dsr__delta">↑{{ percent(d.delta) }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="mz-card">
        <div class="mz-card__head">
          <h3>星级分布</h3>
          <span class="sub">按评价条数</span>
        </div>
        <div class="chart-box">
          <BarChart :items="distItems" :height="188" />
        </div>
      </section>

      <section class="mz-card">
        <div class="mz-card__head">
          <h3>评价标签</h3>
          <span class="sub">买家高频提及</span>
        </div>
        <div class="tags">
          <div v-for="t in summary.tags" :key="t.tag" class="tag-row">
            <span class="tag-row__name">{{ t.tag }}</span>
            <span class="tag-row__bar">
              <i :style="{ width: `${(t.count / tagMax) * 100}%` }" />
            </span>
            <b class="mono">{{ int(t.count) }}</b>
          </div>
        </div>
      </section>
    </div>

    <!-- ============ 评价列表 ============ -->
    <section class="mz-card">
      <el-tabs v-model="params.tab" class="tabs">
        <el-tab-pane v-for="t in REVIEW_TABS" :key="t.key" :name="t.key">
          <template #label>
            <span>{{ t.label }}</span>
            <span
              class="tab-count"
              :class="{ 'is-hot': (t.key === 'bad' || t.key === 'wait') && (extras.tabs?.[t.key] || 0) > 0 }"
            >
              {{ extras.tabs?.[t.key] ?? 0 }}
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="mz-toolbar">
        <span class="mz-toolbar__label">排序</span>
        <el-select v-model="params.sort" style="width: 168px">
          <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button text type="primary" @click="reset()">重置筛选</el-button>
        <div class="spacer" />
        <span class="result-hint">共 <b>{{ total }}</b> 条评价</span>
      </div>

      <div v-loading="loading" class="reviews">
        <article v-for="r in list" :key="r.id" class="review" :class="{ 'is-bad': r.isBad }">
          <div class="review__side">
            <UserAvatar :name="r.buyer.name" :index="r.buyerIndex" :size="38" />
            <div class="review__buyer">
              <b>{{ r.buyer.name }}</b>
              <span class="sub">{{ fmtDate(r.createdAt) }}</span>
            </div>
          </div>

          <div class="review__main">
            <div class="review__head">
              <el-rate :model-value="r.rating" disabled size="small" />
              <StatusTag v-if="r.isBad" tone="coral" size="small">差评</StatusTag>
              <StatusTag :tone="pick(REVIEW_STATUS, r.status).tone" size="small">
                {{ pick(REVIEW_STATUS, r.status).text }}
              </StatusTag>
              <span class="review__id mono">{{ r.orderId }}</span>
            </div>

            <p class="review__content">{{ r.content }}</p>

            <div class="review__meta">
              <span v-if="r.images" class="review__images">
                <el-icon><Picture /></el-icon> {{ r.images }} 张图片
              </span>
              <StatusTag v-for="tag in r.tags" :key="tag" tone="neutral" size="small" :dot="false">
                {{ tag }}
              </StatusTag>
              <span v-if="r.helpful" class="sub">有用 {{ r.helpful }}</span>
            </div>

            <div class="review__product">
              <ProductThumb :thumb="r.product.thumb" :tag="r.product.tag" :size="34" :radius="8" />
              <span class="review__pname">{{ r.product.name }}</span>
              <span class="sub">{{ r.sku }}</span>
            </div>

            <div v-if="r.reply" class="review__reply">
              <b>商家回复：</b>{{ r.reply }}
            </div>
          </div>

          <div class="review__acts">
            <el-button v-if="r.status === 'wait'" size="small" type="primary" @click="openReply(r)">
              回复
            </el-button>
            <el-button v-else size="small" @click="openReply(r)">修改回复</el-button>
            <el-button v-if="r.status !== 'ignored'" size="small" text @click="ignoreOne(r)">
              忽略
            </el-button>
          </div>
        </article>

        <EmptyHint
          v-if="!loading && !list.length"
          icon="ChatLineSquare"
          :title="isFiltered ? '没有匹配的评价' : '暂无评价'"
          :desc="isFiltered ? '试试切换筛选或清空关键词' : '买家签收并评价后这里会出现记录'"
        />
      </div>

      <div v-if="total" class="mz-pager">
        <span class="info">共 <b>{{ total }}</b> 条评价</span>
        <div class="spacer" />
        <el-pagination
          layout="prev, pager, next"
          :current-page="params.page"
          :page-count="pageCount"
          background
          @current-change="changePage"
        />
      </div>
    </section>

    <!-- ============ 回复弹窗 ============ -->
    <el-dialog v-model="replyDialog.visible" title="回复评价" width="540">
      <p class="dialog-desc">买家 {{ replyDialog.buyer }} · 回复将在评价下方公开展示</p>
      <el-input
        v-model="replyDialog.content"
        type="textarea"
        :rows="4"
        placeholder="请输入回复内容，真诚、具体的回复更容易获得复购"
      />
      <div class="quick">
        <div class="quick__title">常用话术</div>
        <div class="quick__list">
          <el-button
            v-for="(q, i) in quickList"
            :key="i"
            size="small"
            @click="replyDialog.content = q"
          >
            话术 {{ i + 1 }}
          </el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="replyDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmReply">发布回复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.row {
  display: grid;
  gap: 16px;
  align-items: start;
}
.row--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tabs :deep(.el-tabs__content) {
  display: none;
}
.result-hint {
  font-size: 12px;
  color: var(--text-3);
}
.result-hint b {
  color: var(--brand);
  font-family: var(--font-mono);
}
.sub {
  font-size: 11px;
  color: var(--text-3);
}

/* ============ DSR ============ */
.dsr {
  padding: 16px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.dsr__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 7px;
}
.dsr__label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-2);
}
.dsr__top b {
  font-size: 17px;
  font-weight: 600;
}
.dsr__bar {
  position: relative;
  height: 6px;
  border-radius: 4px;
  background: #f1eee6;
  overflow: visible;
}
.dsr__bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
/* 行业均值刻度：竖线 + 上方标注，让分数有参照系 */
.dsr__bar em {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 12px;
  border-radius: 2px;
  background: var(--text-3);
  opacity: 0.55;
}
.dsr__foot {
  display: flex;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 6px;
}
.dsr__delta {
  color: var(--green);
  font-family: var(--font-mono);
  font-weight: 600;
}

.chart-box {
  padding: 10px 12px 12px;
}

/* ============ 标签 ============ */
.tags {
  padding: 16px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.tag-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}
.tag-row__name {
  width: 62px;
  flex-shrink: 0;
  color: var(--text-2);
  font-weight: 600;
}
.tag-row__bar {
  flex: 1;
  height: 5px;
  border-radius: 4px;
  background: #f1eee6;
  overflow: hidden;
}
.tag-row__bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #a8e6d3, var(--teal));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.tag-row b {
  width: 40px;
  text-align: right;
  color: var(--text-2);
}

/* ============ 评价列表 ============ */
.reviews {
  padding: 6px 18px 12px;
  min-height: 240px;
}
.review {
  display: flex;
  gap: 14px;
  padding: 16px 0;
  border-bottom: 1px dashed var(--line-soft);
}
.review:last-child {
  border-bottom: none;
}
/* 差评左侧加重色竖条，长列表里一眼可辨 */
.review.is-bad {
  border-left: 2px solid var(--coral);
  padding-left: 13px;
  margin-left: -15px;
  background: linear-gradient(90deg, rgba(255, 90, 46, 0.035), transparent 40%);
}
.review__side {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 152px;
  flex-shrink: 0;
}
.review__buyer b {
  display: block;
  font-size: 12.5px;
}
.review__main {
  flex: 1;
  min-width: 0;
}
.review__head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.review__id {
  margin-left: auto;
  color: var(--text-3);
  font-size: 10.5px;
}
.review__content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
  margin: 9px 0;
}
.review__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.review__images {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--text-2);
}
.review__product {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 11px;
  padding: 8px 11px;
  border-radius: 10px;
  background: #faf8f4;
}
.review__pname {
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 300px;
}
.review__reply {
  margin-top: 11px;
  padding: 9px 12px;
  border-radius: 10px;
  background: var(--brand-soft);
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-2);
}
.review__reply b {
  color: var(--brand);
}
.review__acts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 92px;
  flex-shrink: 0;
}
.review__acts :deep(.el-button) {
  margin-left: 0;
}

.dialog-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 12px;
}
.quick {
  margin-top: 14px;
}
.quick__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-2);
  margin-bottom: 8px;
}
.quick__list {
  display: flex;
  gap: 8px;
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1560px) {
  .row--3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1240px) {
  .row--3 {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 1400px) {
  .mz-toolbar__label {
    display: none;
  }
}
</style>
