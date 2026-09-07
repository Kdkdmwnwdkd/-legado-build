<template>
  <div
    class="settings-wrapper"
    :style="popupTheme"
    :class="{ night: isNight, day: !isNight }"
  >
    <div class="settings-title">设置</div>
    <div class="setting-list">
      <ul>
        <li class="theme-list">
          <i>阅读主题</i>
          <span
            class="theme-item"
            v-for="(themeColor, index) in themeColors"
            :key="index"
            :style="themeColor"
            ref="themes"
            @click="setTheme(index)"
            :class="{ selected: theme == index }"
            ><em v-if="index < 6" class="iconfont">&#58980;</em
            ><em v-else class="moon-icon">{{ moonIcon }}</em></span
          >
        </li>
        <li class="font-list">
          <i>正文字体</i>
          <span
            class="font-item"
            v-for="(font, index) in fonts"
            :key="index"
            :class="{ selected: selectedFont == index }"
            @click="setFont(index)"
            >{{ font }}</span
          >
        </li>
        <li class="font-list">
          <i>自定字体</i>
          <el-tooltip effect="dark" content="自定义的字体名称" placement="top">
            <input
              type="text"
              class="font-item font-item-input"
              v-model="customFontName"
              placeholder="请输入自定义的字体名称"
            />
          </el-tooltip>

          <el-popover
            placement="top"
            width="270"
            trigger="click"
            v-model:visible="customFontSavePopVisible"
          >
            <p>
              已经安装在您的设备上的字体请确认输入的字体名称完整无误，或者从网络下载字体。
            </p>
            <div style="text-align: right; margin: 0">
              <el-button
                size="small"
                plain
                @click="customFontSavePopVisible = false"
                >取消</el-button
              >
              <el-button type="primary" size="small" @click="setCustomFont()"
                >确定</el-button
              >
              <el-button type="primary" size="small" @click="loadFontFromURL()"
                >网络下载</el-button
              >
            </div>
            <template #reference>
              <span type="text" class="font-item">保存</span>
            </template>
          </el-popover>
        </li>
        <li class="font-size">
          <i>字体大小</i>
          <div class="resize">
            <span class="less" @click="lessFontSize"
              ><em class="iconfont">&#58966;</em></span
            ><b></b> <span class="lang">{{ fontSize }}</span
            ><b></b>
            <span class="more" @click="moreFontSize"
              ><em class="iconfont">&#58976;</em></span
            >
          </div>
        </li>
        <li class="letter-spacing">
          <i>字距</i>
          <div class="resize">
            <span class="less" @click="lessLetterSpacing"
              ><em class="iconfont">&#58966;</em></span
            ><b></b> <span class="lang">{{ spacing.letter.toFixed(2) }}</span
            ><b></b>
            <span class="more" @click="moreLetterSpacing"
              ><em class="iconfont">&#58976;</em></span
            >
          </div>
        </li>
        <li class="line-spacing">
          <i>行距</i>
          <div class="resize">
            <span class="less" @click="lessLineSpacing"
              ><em class="iconfont">&#58966;</em></span
            ><b></b> <span class="lang">{{ spacing.line.toFixed(1) }}</span
            ><b></b>
            <span class="more" @click="moreLineSpacing"
              ><em class="iconfont">&#58976;</em></span
            >
          </div>
        </li>
        <li class="paragraph-spacing">
          <i>段距</i>
          <div class="resize">
            <div class="resize">
              <span class="less" @click="lessParagraphSpacing"
                ><em class="iconfont">&#58966;</em></span
              ><b></b>
              <span class="lang">{{ spacing.paragraph.toFixed(1) }}</span
              ><b></b>
              <span class="more" @click="moreParagraphSpacing"
                ><em class="iconfont">&#58976;</em></span
              >
            </div>
          </div>
        </li>
        <li class="read-width" v-if="!store.miniInterface">
          <i>页面宽度</i>
          <div class="resize">
            <span class="less" @click="lessReadWidth"
              ><em class="iconfont">&#58965;</em></span
            ><b></b> <span class="lang">{{ readWidth }}</span
            ><b></b>
            <span class="more" @click="moreReadWidth"
              ><em class="iconfont">&#58975;</em></span
            >
          </div>
        </li>
        <li class="paragraph-spacing">
          <i>翻页速度</i>
          <div class="resize">
            <div class="resize">
              <span class="less" @click="lessJumpDuration">
                <em class="iconfont">&#xe625;</em>
              </span>
              <b></b> <span class="lang">{{ jumpDuration }}</span
              ><b></b>
              <span class="more" @click="moreJumpDuration"
                ><em class="iconfont">&#xe626;</em></span
              >
            </div>
          </div>
        </li>
        <li class="infinite-loading">
          <i>无限加载</i>
          <span
            class="infinite-loading-item"
            :key="0"
            :class="{ selected: infiniteLoading == false }"
            @click="setInfiniteLoading(false)"
            >关闭</span
          >
          <span
            class="infinite-loading-item"
            :key="1"
            :class="{ selected: infiniteLoading == true }"
            @click="setInfiniteLoading(true)"
            >开启</span
          >
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import '../assets/fonts/popfont.css'
import '../assets/fonts/iconfont.css'
import settings from '../config/themeConfig'
import API from '@api'
import { useDebounceFn } from '@vueuse/shared'

const store = useBookStore()
const saveConfigDebounce = useDebounceFn(
  () => API.saveReadConfig(store.config),
  500,
)
//阅读界面设置改变时保存同步配置
watch(
  () => store.config,
  () => {
    saveConfigDebounce()
  },
  {
    deep: 2, //深度为2
  },
)

//主题颜色
const theme = computed(() => store.theme)
const isNight = computed(() => store.isNight)
const moonIcon = computed(() => (theme.value == 6 ? '' : ''))
const themeColors = [
  {
    background: 'rgba(250, 245, 235, 0.8)',
  },
  {
    background: 'rgba(245, 234, 204, 0.8)',
  },
  {
    background: 'rgba(230, 242, 230, 0.8)',
  },
  {
    background: 'rgba(228, 241, 245, 0.8)',
  },
  {
    background: 'rgba(245, 228, 228, 0.8)',
  },
  {
    background: 'rgba(224, 224, 224, 0.8)',
  },
  {
    background: 'rgba(0, 0, 0, 0.5)',
  },
]
const popupTheme = computed(() => {
  return {
    background: settings.themes[theme.value].popup,
  }
})
const setTheme = (theme: number) => {
  store.config.theme = theme
}

//预置字体
const fonts = ref(['雅黑', '宋体', '楷书'])
const setFont = (font: number) => {
  store.config.font = font
}
const selectedFont = computed(() => {
  return store.config.font
})
//自定义字体
const customFontName = ref(store.config.customFontName)
const customFontSavePopVisible = ref(false)
const setCustomFont = () => {
  customFontSavePopVisible.value = false
  store.config.font = -1
  store.config.customFontName = customFontName.value
}
// 加载网络字体
const loadFontFromURL = () => {
  customFontSavePopVisible.value = false
  ElMessageBox.prompt('请输入 字体网络链接', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPattern: /^https?:.+$/,
    inputErrorMessage: 'url 形式不正确',
    beforeClose: (action, instance, done) => {
      if (action === 'confirm') {
        instance.confirmButtonLoading = true
        instance.confirmButtonText = '下载中……'
        // instance.inputValue
        const url = instance.inputValue
        if (typeof FontFace !== 'function') {
          ElMessage.error('浏览器不支持FontFace')
          return done()
        }
        const fontface = new FontFace(customFontName.value, `url("${url}")`)
        document.fonts.add(fontface)
        fontface
          .load()
          //API.getBookShelf()
          .then(function () {
            instance.confirmButtonLoading = false
            ElMessage.info('字体加载成功！')
            setCustomFont()
            done()
          })
          .catch(function (error) {
            instance.confirmButtonLoading = false
            instance.confirmButtonText = '确定'
            ElMessage.error('下载失败，请检查您输入的 url')
            throw error
          })
      } else {
        done()
      }
    },
  })
}

//字体大小
const fontSize = computed(() => {
  return store.config.fontSize
})
const moreFontSize = () => {
  if (store.config.fontSize < 48) store.config.fontSize += 2
}
const lessFontSize = () => {
  if (store.config.fontSize > 12) store.config.fontSize -= 2
}

//字 行 段落间距
const spacing = computed(() => {
  return store.config.spacing
})
const lessLetterSpacing = () => {
  store.config.spacing.letter -= 0.01
}
const moreLetterSpacing = () => {
  store.config.spacing.letter += 0.01
}
const lessLineSpacing = () => {
  store.config.spacing.line -= 0.1
}
const moreLineSpacing = () => {
  store.config.spacing.line += 0.1
}
const lessParagraphSpacing = () => {
  store.config.spacing.paragraph -= 0.1
}
const moreParagraphSpacing = () => {
  store.config.spacing.paragraph += 0.1
}

//页面宽度
const readWidth = computed(() => {
  return store.config.readWidth
})
const moreReadWidth = () => {
  // 此时会截断页面
  if (store.config.readWidth + 160 + 2 * 68 > window.innerWidth) return
  store.config.readWidth += 160
}
const lessReadWidth = () => {
  if (store.config.readWidth > 640) store.config.readWidth -= 160
}

//翻页速度
const jumpDuration = computed(() => {
  return store.config.jumpDuration
})
const moreJumpDuration = () => {
  store.config.jumpDuration += 100
}
const lessJumpDuration = () => {
  if (store.config.jumpDuration === 0) return
  store.config.jumpDuration -= 100
}

//无限加载
const infiniteLoading = computed(() => {
  return store.config.infiniteLoading
})
const setInfiniteLoading = (loading: boolean) => {
  store.config.infiniteLoading = loading
}
</script>

<style lang="scss" scoped>
:deep(.iconfont) {
  font-family: iconfont;
  font-style: normal;
}

:deep(.moon-icon) {
  font-family: iconfont;
  font-style: normal;
}

.settings-wrapper {
  user-select: none;
  margin: -12px;
  text-align: left;
  padding: 24px 20px 24px 20px;
  border-radius: var(--legado-radius, 14px);

  .settings-title {
    font-size: 12px;
    font-weight: 700;
    line-height: 18px;
    margin-bottom: 20px;
    color: var(--legado-accent, #89b4fa);
    letter-spacing: 2px;
    text-transform: uppercase;
    position: relative;
    padding-left: 12px;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 1px;
      bottom: 1px;
      width: 3px;
      border-radius: 2px;
      background: var(--legado-accent, #89b4fa);
    }
  }

  .setting-list {
    max-height: calc(70vh - 40px);
    overflow: auto;
    padding-right: 4px;

    &::-webkit-scrollbar {
      width: 4px;
    }
    &::-webkit-scrollbar-thumb {
      background: rgba(0, 0, 0, 0.08);
      border-radius: 2px;
    }

    ul {
      list-style: none outside none;
      margin: 0;
      padding: 0;

      li {
        list-style: none outside none;
        padding: 8px 0;

        i {
          font:
            11px / 16px -apple-system,
            'PingFang SC',
            sans-serif;
          display: inline-block;
          min-width: 52px;
          margin-right: 12px;
          vertical-align: middle;
          color: var(--legado-text-dim, #6c6f85);
          font-weight: 500;
        }

        .theme-item {
          line-height: 28px;
          width: 28px;
          height: 28px;
          margin-right: 10px;
          margin-top: 4px;
          border-radius: 50%;
          display: inline-block;
          cursor: pointer;
          text-align: center;
          vertical-align: middle;
          transition: all var(--legado-transition, 0.28s ease);

          .iconfont {
            display: none;
          }

          &:hover {
            transform: scale(1.15);
          }
        }

        .selected {
          color: var(--legado-accent, #89b4fa);

          .iconfont {
            display: inline;
          }
        }
      }

      .font-list,
      .infinite-loading {
        margin-top: 8px;

        .font-item,
        .infinite-loading-item {
          width: 70px;
          height: 30px;
          cursor: pointer;
          margin-right: 8px;
          border-radius: var(--legado-radius-sm, 10px);
          text-align: center;
          vertical-align: middle;
          display: inline-block;
          font:
            12px / 30px -apple-system,
            'PingFang SC',
            'Microsoft YaHei',
            sans-serif;
          transition: all var(--legado-transition, 0.28s ease);
        }
        .font-item-input {
          width: 148px;
          color: var(--legado-text, #1e1e2e);
          padding: 0 8px;
        }
        .selected {
          color: var(--legado-accent, #89b4fa);
          border: 1px solid var(--legado-accent, #89b4fa);
          background: rgba(137, 180, 250, 0.06);
        }

        .font-item:hover,
        .infinite-loading-item:hover {
          border: 1px solid var(--legado-accent, #89b4fa);
          color: var(--legado-accent, #89b4fa);
        }
      }

      .font-size,
      .read-width,
      .letter-spacing,
      .line-spacing,
      .paragraph-spacing {
        margin-top: 8px;

        .resize {
          display: inline-block;
          width: 250px;
          height: 30px;
          vertical-align: middle;
          border-radius: var(--legado-radius-sm, 10px);
          overflow: hidden;

          span {
            width: 80px;
            height: 30px;
            line-height: 30px;
            display: inline-block;
            cursor: pointer;
            text-align: center;
            vertical-align: middle;
            transition: background 0.2s ease, color 0.2s ease;

            em {
              font-style: normal;
            }
          }

          .less:hover,
          .more:hover {
            color: var(--legado-accent, #89b4fa);
            background: rgba(137, 180, 250, 0.06);
          }

          .lang {
            color: var(--legado-text-faint, #a0a3b8);
            font-weight: 500;
            font-family: -apple-system, 'PingFang SC', monospace;
            font-size: 12px;
          }

          b {
            display: inline-block;
            height: 18px;
            vertical-align: middle;
          }
        }
      }
    }
  }
}

.night {
  :deep(.theme-item) {
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  :deep(.selected) {
    border: 1px solid var(--legado-accent, #89b4fa) !important;
  }

  :deep(.moon-icon) {
    color: var(--legado-accent, #89b4fa);
  }

  :deep(.font-list),
  .infinite-loading {
    .font-item,
    .infinite-loading-item {
      border: 1px solid rgba(255, 255, 255, 0.08);
      background: rgba(30, 30, 46, 0.5);
    }
  }

  :deep(.resize) {
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(30, 30, 46, 0.5);

    b {
      border-right: 1px solid rgba(255, 255, 255, 0.08);
    }
  }
}

.day {
  :deep(.theme-item) {
    border: 1px solid rgba(0, 0, 0, 0.06);
  }

  :deep(.selected) {
    border: 1px solid var(--legado-accent, #89b4fa);
  }

  :deep(.moon-icon) {
    display: inline;
    color: rgba(255, 255, 255, 0.2);
  }

  :deep(.font-list),
  .infinite-loading {
    .font-item,
    .infinite-loading-item {
      background: rgba(255, 255, 255, 0.5);
      border: 1px solid rgba(0, 0, 0, 0.06);
    }
  }

  :deep(.resize) {
    border: 1px solid rgba(0, 0, 0, 0.06);
    background: rgba(255, 255, 255, 0.5);

    b {
      border-right: 1px solid rgba(0, 0, 0, 0.06);
    }
  }
}

@media screen and (max-width: 500px) {
  .settings-wrapper i {
    display: flex !important;
    flex-wrap: wrap;
    padding-bottom: 5px !important;
  }
}
</style>
