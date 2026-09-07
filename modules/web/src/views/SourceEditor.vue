<template>
  <div class="editor">
    <source-tab-form class="left" :config="config" />
    <tool-bar />
    <source-tab-tools class="right" />
  </div>
</template>
<script setup lang="ts">
import bookSourceConfig from '@/config/bookSourceEditConfig'
import rssSourceConfig from '@/config/rssSourceEditConfig'
import '@/assets/sourceeditor.css'
import { useDark } from '@vueuse/core'
import type { SourceConfig } from '@/config/sourceConfig'

useDark()

let config: SourceConfig
const isBookSource = ref<boolean>(/bookSource/i.test(location.href))
provide('isBookSource', isBookSource)
if (isBookSource.value) {
  config = bookSourceConfig as SourceConfig
  document.title = '书源管理'
} else {
  config = rssSourceConfig as SourceConfig
  document.title = '订阅源管理'
}
</script>
<style lang="scss" scoped>
.editor {
  display: flex;
  height: 100vh;
  overflow: hidden;
  gap: 0;
  padding: 0;
  background: linear-gradient(135deg, #181825 0%, #1e1e2e 50%, #181825 100%);
  .left {
    flex: 1;
    margin: 12px 0 12px 12px;
    border-radius: var(--legado-radius, 14px);
    overflow: hidden;
    background: var(--legado-bg, #1e1e2e);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.06);
  }
  .right {
    flex: 1;
    width: 380px;
    margin: 12px 12px 12px 0;
    border-radius: var(--legado-radius, 14px);
    overflow: hidden;
    background: var(--legado-bg, #1e1e2e);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.06);
  }
}
</style>
