<template>
  <el-input
    v-if="isBookSource"
    id="debug-key"
    v-model="searchKey"
    placeholder="搜索书名、作者"
    :prefix-icon="Search"
    style="padding-bottom: 4px"
    @keydown.enter="startDebug"
  />
  <el-input
    id="debug-text"
    v-model="printDebug"
    type="textarea"
    readonly
    :rows="29"
    placeholder="这里用于输出调试信息"
  />
</template>

<script setup lang="ts">
import API from '@api'
import { Search } from '@element-plus/icons-vue'

const store = useSourceStore()

const printDebug = ref('')
const searchKey = ref('')

watch(
  () => store.isDebuging,
  () => {
    if (store.isDebuging) startDebug()
  },
)

const appendDebugMsg = (msg: string) => {
  const debugDom = document.querySelector('#debug-text')
  debugDom!.scrollTop = debugDom!.scrollHeight
  printDebug.value += msg + '\n'
}
const startDebug = async () => {
  printDebug.value = ''
  try {
    await API.saveSource(store.currentSource)
  } catch (e) {
    store.debugFinish()
    throw e
  }
  API.debug(
    store.currentSourceUrl,
    searchKey.value || store.searchKey,
    appendDebugMsg,
    store.debugFinish,
  )
}

const isBookSource = computed(() => {
  return /bookSource/i.test(window.location.href)
})
</script>

<style lang="scss" scoped>
:deep(#debug-key) {
  padding-bottom: 4px;
  .el-input__wrapper {
    border-radius: var(--legado-radius-sm, 8px) !important;
    background: rgba(255, 255, 255, 0.03) !important;
    border: 1px solid rgba(255, 255, 255, 0.08) !important;
    box-shadow: none !important;
    transition: all 0.25s ease;
    &:hover, &.is-focus {
      border-color: rgba(137, 180, 250, 0.4) !important;
      background: rgba(255, 255, 255, 0.05) !important;
    }
  }
  .el-input__inner {
    color: rgba(255, 255, 255, 0.85);
    &::placeholder {
      color: rgba(255, 255, 255, 0.25);
    }
  }
}
:deep(#debug-text) {
  height: calc(100vh - 45px - 36px - 5px);
  .el-textarea__inner {
    border-radius: var(--legado-radius, 14px) !important;
    background: rgba(0, 0, 0, 0.2) !important;
    border: 1px solid rgba(255, 255, 255, 0.08) !important;
    box-shadow: none !important;
    color: rgba(255, 255, 255, 0.75);
    font-family: 'SF Mono', 'Fira Code', 'Consolas', 'Monaco', monospace;
    font-size: 13px;
    line-height: 1.6;
    &::placeholder {
      color: rgba(255, 255, 255, 0.2);
    }
  }
}
</style>
