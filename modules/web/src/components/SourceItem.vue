<template>
  <el-checkbox
    size="large"
    border
    :value="sourceUrl"
    :class="{
      error: isSaveError,
      edit: sourceUrl == currentSourceUrl,
    }"
  >
    {{ getSourceName(source) }}
    <el-button text :icon="Edit" @click="handleSourceClick(source)" />
  </el-checkbox>
</template>

<script setup lang="ts">
import { Edit } from '@element-plus/icons-vue'
import { getSourceUniqueKey, getSourceName } from '@/utils/souce'
import type { Source } from '@/source'

const props = defineProps<{
  source: Source
}>()

const store = useSourceStore()

const currentSourceUrl = computed(() => store.currentSourceUrl)
const sourceUrl = computed(() => getSourceUniqueKey(props.source))

const handleSourceClick = (source: Source) => {
  store.changeCurrentSource(source)
}
const isSaveError = computed(() => {
  const map = store.savedSourcesMap
  if (map.size == 0) return false
  return !map.has(sourceUrl.value)
})
</script>
<style lang="scss" scoped>
:deep(.el-checkbox__label) {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
  transition: color 0.2s ease;
}
:deep(.el-checkbox) {
  border-radius: var(--legado-radius-sm, 8px);
  transition: all 0.25s ease;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.02);
  padding: 8px 12px;
  &:hover {
    background: rgba(137, 180, 250, 0.04);
    border-color: rgba(137, 180, 250, 0.15);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    :deep(.el-checkbox__label) {
      color: rgba(255, 255, 255, 0.9);
    }
  }
}
:deep(.el-checkbox.is-checked) {
  background: rgba(137, 180, 250, 0.06);
  border-color: rgba(137, 180, 250, 0.25);
}
:deep(.el-button) {
  color: rgba(255, 255, 255, 0.35);
  &:hover {
    color: var(--legado-accent, #89b4fa);
  }
}
.error {
  border-color: rgba(239, 121, 116, 0.4) !important;
  color: #ef7b74 !important;
  --el-checkbox-checked-text-color: #ef7b74;
  --el-checkbox-checked-bg-color: #ef7b74;
  --el-checkbox-checked-input-border-color: #ef7b74;
}
.edit {
  border-color: rgba(137, 180, 250, 0.3) !important;
  background: rgba(137, 180, 250, 0.04);
}
</style>
