<template>
  <el-tabs v-model="current_tab">
    <el-tab-pane
      v-for="(tab, index) in tabData"
      :key="tab[0]"
      :name="tab[0]"
      :label="tab[1]"
    >
      <source-json v-if="index == 0" />
      <source-debug v-if="index == 1" />
      <source-list v-if="index == 2" />
      <source-help v-if="index == 3" />
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { useSourceStore } from '@/store'

const store = useSourceStore()

const current_tab = computed({
  get: () => store.currentTab,
  set: val => (store.currentTab = val),
})

const tabData = ref([
  ['editTab', '编辑源'],
  ['editDebug', '调试源'],
  ['editList', '源列表'],
  ['editHelp', '帮助信息'],
])
</script>

<style lang="scss" scoped>
:deep(.el-tabs__header) {
  margin: 0 0 4px 0;
  padding: 0 8px;
  background: rgba(0, 0, 0, 0.15);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
:deep(.el-tabs__item) {
  height: 38px;
  line-height: 38px;
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.4);
  transition: all 0.2s ease;
  &:hover {
    color: rgba(255, 255, 255, 0.65);
  }
  &.is-active {
    color: var(--legado-accent, #89b4fa);
  }
}
:deep(.el-tabs__active-line) {
  background: var(--legado-accent, #89b4fa);
  height: 2px;
  border-radius: 2px;
}
:deep(.el-tab-pane) {
  padding: 8px 4px;
}
</style>
