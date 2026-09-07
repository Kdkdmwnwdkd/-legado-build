<template>
  <el-tabs id="source-edit">
    <el-tab-pane
      v-for="{ name, children } in Object.values(config)"
      :label="name"
      :key="name"
    >
      <el-form label-position="right" label-width="auto">
        <el-form-item
          v-for="{
            type,
            title,
            namespace,
            id,
            array,
            hint,
            required = false,
          } in children"
          :label="title"
          :key="title"
          :required="required"
        >
          <el-input
            v-if="type == 'String' && typeof namespace == 'undefined'"
            type="textarea"
            v-model="currentSource[id]"
            :placeholder="hint"
            autosize
          />
          <el-input
            v-if="type == 'String' && typeof namespace != 'undefined'"
            type="textarea"
            v-model="currentSource[namespace][id]"
            :placeholder="hint"
            autosize
          />

          <el-switch
            v-if="(type as string) === 'Boolean'"
            v-model="currentSource[id]"
          />

          <el-input-number
            v-if="(type as string) === 'Number'"
            v-model="currentSource[id]"
            :min="0"
          />

          <el-select
            v-if="(type as string) === 'Array'"
            v-model="currentSource[id]"
          >
            <el-option
              v-for="(optionName, index) in array"
              :value="index"
              :key="optionName"
              :label="optionName"
            />
          </el-select>
        </el-form-item>
      </el-form>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import type { SourceConfig } from '@/config/sourceConfig'

const store = useSourceStore()
defineProps<{ config: SourceConfig }>()

const currentSource = computed(() => store.currentSource)
/* 
修改currentSource的属性 没有直接修改本身
const { currentSource } = storeToRefs(store);
 */
</script>

<style lang="scss" scoped>
:deep(.el-tabs__header) {
  margin: 0;
  padding: 0 16px;
  background: rgba(0, 0, 0, 0.15);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
:deep(.el-tabs__item) {
  height: 42px;
  line-height: 42px;
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.45);
  transition: all 0.2s ease;
  &:hover {
    color: rgba(255, 255, 255, 0.7);
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
  height: calc(100vh - 55px - 42px);
  padding: 16px 20px 16px 16px;
  overflow-y: auto;
  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.1);
    border-radius: 4px;
  }
}
:deep(.el-form-item) {
  margin-bottom: 18px;
}
:deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.55);
}
:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: var(--legado-radius-sm, 8px) !important;
  background: rgba(255, 255, 255, 0.03) !important;
  border: 1px solid rgba(255, 255, 255, 0.08) !important;
  box-shadow: none !important;
  transition: all 0.25s ease;
  &:hover, &:focus, &.is-focus {
    border-color: rgba(137, 180, 250, 0.4) !important;
    background: rgba(255, 255, 255, 0.05) !important;
  }
}
:deep(.el-textarea__inner) {
  color: rgba(255, 255, 255, 0.85);
  &::placeholder {
    color: rgba(255, 255, 255, 0.25);
  }
}
</style>
