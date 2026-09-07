<script setup lang="ts">
import { Link } from '@element-plus/icons-vue'
const isBookSource = inject<Ref<boolean>>('isBookSource', ref(true))
const sourceTitle = computed(() => {
  return isBookSource.value ? '书源' : '订阅源'
})
const sourcePath = computed(() => {
  return isBookSource.value ? 'ruleHelp' : 'rssRuleHelp'
})
</script>
<template>
  <el-link :icon="Link" href="/help/#appHelp" target="_blank"
    >APP帮助文档</el-link
  ><br />
  <el-link :icon="Link" :href="`/help/#${sourcePath}`" target="_blank"
    >{{ sourceTitle }}制作教程</el-link
  ><br />
  <el-link :icon="Link" href="/help/#jsHelp" target="_blank"
    >js变量和函数</el-link
  ><br />
  <el-link :icon="Link" href="/help/#xpathHelp" target="_blank"
    >xpath语法教程</el-link
  ><br />
  <el-link :icon="Link" href="/help/#regexHelp" target="_blank"
    >正则表达式教程</el-link
  ><br />
  <el-link :icon="Link" href="/help/#txtTocRuleHelp" target="_blank"
    >txt目录正则说明</el-link
  ><br />
  <el-link :icon="Link" href="/help/#debugHelp" target="_blank"
    >书源调试说明</el-link
  ><br />
  <el-link :icon="Link" href="/help/#httpTTSHelp" target="_blank"
    >在线朗读规则</el-link
  ><br />
  <el-link :icon="Link" href="/help/#webDavBookHelp" target="_blank">
    WebDav书籍简明使用教程</el-link
  ><br />
  <el-link :icon="Link" href="/help/#webDavHelp" target="_blank">
    WebDav备份教程</el-link
  ><br />
  <el-link :icon="Link" href="https://regexr-cn.com/" target="_blank"
    >正则表达式在线验证工具</el-link
  ><br />
  <div style="margin-top: 20px">
    <span
      ><el-text
        ><code>^$()[]{}.?+*|</code> 这些是Java正则特殊符号,匹配需转义</el-text
      ></span
    ><br />
    <span
      ><el-text><code>(?s)</code> 前缀表示跨行解析</el-text></span
    ><br />
    <span
      ><el-text><code>(?m)</code> 前缀表示逐行匹配</el-text></span
    ><br />
    <span
      ><el-text><code>(?i)</code> 前缀表示忽略大小写</el-text></span
    ><br />
  </div>
</template>

<style lang="scss" scoped>
.el-link {
  padding: 8px 12px;
  transition: all 0.25s ease;
  display: flex;
  align-items: center;
  border-radius: var(--legado-radius-sm, 8px);
  color: rgba(255, 255, 255, 0.6);
  &:hover {
    color: var(--legado-accent, #89b4fa);
    background: rgba(137, 180, 250, 0.08);
    transform: translateX(4px);
  }
}
.el-text {
  padding-top: 20px;
  display: block;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.4);
  code {
    padding: 2px 6px;
    border-radius: 4px;
    background: rgba(255, 255, 255, 0.06);
    color: var(--legado-accent, #89b4fa);
    font-size: 12px;
    font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  }
}
</style>
