<template>
  <div :class="{ 'index-wrapper': true, night: isNight, day: !isNight }">
    <aside class="sidebar">
      <div class="sidebar-inner">
        <div class="brand">
          <div class="brand-mark">R</div>
          <div class="brand-text">
            <div class="navigation-title">阅读</div>
            <div class="navigation-sub-title">Legado</div>
          </div>
        </div>
        <div class="sidebar-sections">
          <div class="recent-wrapper">
            <div class="recent-title">最近阅读</div>
            <div class="reading-recent">
              <el-tag
                :type="
                  readingRecent.name == '尚无阅读记录' ? 'warning' : 'primary'
                "
                class="recent-book"
                size="large"
                @click="
                  toDetail(
                    readingRecent.bookUrl,
                    readingRecent.name,
                    readingRecent.author,
                    readingRecent.chapterIndex,
                    readingRecent.chapterPos,
                    readingRecent.isSeachBook,
                    true,
                  )
                "
                :class="{ 'no-point': readingRecent.bookUrl == '' }"
              >
                {{ readingRecent.name }}
              </el-tag>
            </div>
          </div>
          <div class="setting-wrapper">
            <div class="setting-title">连接</div>
            <div class="setting-item">
              <el-tag
                :type="connectType"
                size="large"
                class="setting-connect"
                :class="{ 'no-point': newConnect }"
                @click="setLegadoRetmoteUrl"
              >
                {{ connectStatus }}
              </el-tag>
            </div>
          </div>
        </div>
        <div class="bottom-icons">
          <a
            href="https://github.com/gedoor/legado_web_bookshelf"
            target="_blank"
            class="github-link"
          >
            <img :src="githubUrl" alt="" />
          </a>
        </div>
      </div>
    </aside>
    <main class="shelf-wrapper" ref="shelfWrapper">
      <book-items
        :books="books"
        @bookClick="handleBookClick"
      ></book-items>
    </main>
  </div>
</template>

<script setup lang="ts">
import '@/assets/bookshelf.css'
import '@/assets/fonts/shelffont.css'
import { useBookStore } from '@/store'
import githubUrl from '@/assets/imgs/github.png'
import { useLoading } from '@/hooks/loading'
import { baseURL_localStorage_key } from '@/api/axios'
import API, {
  legado_http_entry_point,
  parseLeagdoHttpUrlWithDefault,
  setApiEntryPoint,
} from '@api'
import { validatorHttpUrl } from '@/utils/utils'
import type { Book } from '@/book'
import type { webReadConfig } from '@/web'

const store = useBookStore()
const isNight = computed(() => store.isNight)

/** shortcuts of `store.setConfig` */
const applyReadConfig = (config?: webReadConfig) => {
  try {
    if (config !== undefined) store.setConfig(config)
  } catch {
    ElMessage.info('阅读界面配置解析错误')
  }
}

const readingRecent = ref<typeof store.readingBook>({
  name: '尚无阅读记录',
  author: '',
  bookUrl: '',
  chapterIndex: 0,
  chapterPos: 0,
  isSeachBook: false,
})

const shelfWrapper = ref<HTMLElement>()
//const shelfWrapper = useTemplateRef<HTMLElement>("shelfWrapper")
const { loadingWrapper } = useLoading(
  shelfWrapper,
  '正在获取书籍信息',
)

// 书架书籍
const shelf = computed(() => store.shelf)
const books = shelf

//连接状态
const connectionStore = useConnectionStore()
const { connectStatus, connectType, newConnect } = storeToRefs(connectionStore)

const setLegadoRetmoteUrl = () => {
  ElMessageBox.prompt(
    '请输入 后端地址 ( 如：http://127.0.0.1:9527 或者通过内网穿透的地址)',
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: legado_http_entry_point,
      inputValidator: value => validatorHttpUrl(value),
      inputErrorMessage: '输入的格式不对',
      beforeClose: (action, instance, done) => {
        if (action === 'confirm') {
          connectionStore.setNewConnect(true)
          instance.confirmButtonLoading = true
          instance.confirmButtonText = '校验中……'
          // instance.inputValue
          const url = new URL(instance.inputValue).toString()
          API.getReadConfig(url)
            .then(function (config) {
              connectionStore.setNewConnect(false)
              applyReadConfig(config)
              instance.confirmButtonLoading = false
              setApiEntryPoint(...parseLeagdoHttpUrlWithDefault(url))
              if (url === location.origin) {
                localStorage.removeItem(baseURL_localStorage_key)
              } else {
                localStorage.setItem(baseURL_localStorage_key, url)
              }
              store.loadBookShelf()
              done()
            })
            .catch(function (error) {
              connectionStore.setNewConnect(false)
              instance.confirmButtonLoading = false
              instance.confirmButtonText = '确定'
              throw error
            })
        } else {
          done()
        }
      },
    },
  )
}

const router = useRouter()
const handleBookClick = (book: Book) => {
  const {
    bookUrl,
    name,
    author,
    durChapterIndex = 0,
    durChapterPos = 0,
  } = book

  toDetail(bookUrl, name, author, durChapterIndex, durChapterPos, false)
}
const toDetail = (
  bookUrl: string,
  bookName: string,
  bookAuthor: string,
  chapterIndex: number,
  chapterPos: number,
  isSeachBook: boolean | undefined = false,
  fromReadRecentClick = false,
) => {
  if (bookName === '尚无阅读记录') return
  // 最近书籍不再书架上 自动搜索
  if (
    fromReadRecentClick &&
    shelf.value.every(book => book.bookUrl !== bookUrl)
  ) {
    ElMessage.info('\u8be5\u4e66\u5df2\u4e0d\u5728\u4e66\u67b6\u4e2d')
    return
  }
  sessionStorage.setItem('bookUrl', bookUrl)
  sessionStorage.setItem('bookName', bookName)
  sessionStorage.setItem('bookAuthor', bookAuthor)
  sessionStorage.setItem('chapterIndex', String(chapterIndex))
  sessionStorage.setItem('chapterPos', String(chapterPos))
  sessionStorage.setItem('isSeachBook', String(isSeachBook))
  readingRecent.value = {
    name: bookName,
    author: bookAuthor,
    bookUrl,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  localStorage.setItem('readingRecent', JSON.stringify(readingRecent.value))
  router.push({
    path: '/chapter',
  })
}

const loadShelf = async () => {
  await store.loadWebConfig()
  await store.saveBookProgress()
  //确保各种网络情况下同步请求先完成
  await store.loadBookShelf()
}

onMounted(() => {
  //获取最近阅读书籍
  const readingRecentStr = localStorage.getItem('readingRecent')
  if (readingRecentStr != null) {
    readingRecent.value = JSON.parse(readingRecentStr)
    if (typeof readingRecent.value.chapterIndex == 'undefined') {
      readingRecent.value.chapterIndex = 0
    }
  }
  console.log('bookshelf mounted')
  loadingWrapper(loadShelf())
})
</script>

<style lang="scss" scoped>
.index-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: row;

  .sidebar {
    width: 220px;
    min-width: 220px;
    background: var(--legado-sidebar-bg, #1e1e2e);
    position: relative;
    display: flex;
    flex-direction: column;

    .sidebar-inner {
      padding: 36px 24px;
      display: flex;
      flex-direction: column;
      height: 100%;
      box-sizing: border-box;
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 40px;

      .brand-mark {
        width: 36px;
        height: 36px;
        border-radius: 10px;
        background: var(--legado-accent, #89b4fa);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 18px;
        font-weight: 700;
        color: #1e1e2e;
        flex-shrink: 0;
      }

      .brand-text {
        .navigation-title {
          font-size: 18px;
          font-weight: 600;
          color: var(--legado-sidebar-fg, #cdd6f4);
          line-height: 1.2;
        }

        .navigation-sub-title {
          font-size: 11px;
          font-weight: 400;
          color: var(--legado-sidebar-dim, #6c7086);
          letter-spacing: 2px;
          text-transform: uppercase;
          margin-top: 2px;
        }
      }
    }

    .sidebar-sections {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 28px;
    }

    .recent-wrapper,
    .setting-wrapper {
      .recent-title,
      .setting-title {
        font-size: 10px;
        font-weight: 600;
        color: var(--legado-sidebar-dim, #6c7086);
        text-transform: uppercase;
        letter-spacing: 2px;
        margin-bottom: 10px;
      }

      .reading-recent {
        .recent-book {
          font-size: 11px;
          cursor: pointer;
          border-radius: var(--legado-radius-sm, 10px);
          border: none;
          transition: all var(--legado-transition, 0.28s ease);
          max-width: 100%;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }

      .setting-item {
        .setting-connect {
          font-size: 10px;
          cursor: pointer;
          border-radius: var(--legado-radius-sm, 10px);
          border: none;
          transition: all var(--legado-transition, 0.28s ease);
        }
      }
    }

    .no-point {
      pointer-events: none;
      opacity: 0.5;
    }

    .bottom-icons {
      margin-top: auto;
      padding-top: 20px;

      .github-link {
        display: inline-flex;
        opacity: 0.4;
        transition: opacity var(--legado-transition, 0.28s ease);

        &:hover {
          opacity: 0.8;
        }

        img {
          width: 20px;
          height: 20px;
          filter: brightness(0) invert(1);
          opacity: 0.7;
        }
      }
    }
  }

  .shelf-wrapper {
    flex: 1;
    padding: 32px 40px;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    overflow: hidden;
    background: var(--legado-bg, #f0f1f5);
  }
}

@media screen and (max-width: 750px) {
  .index-wrapper {
    overflow-x: hidden;
    flex-direction: column;

    .sidebar {
      width: 100%;
      min-width: unset;

      .sidebar-inner {
        padding: 14px 20px;
        flex-direction: row;
        align-items: center;
        gap: 16px;
        height: auto;
      }

      .brand {
        margin-bottom: 0;
        flex-shrink: 0;

        .brand-mark {
          width: 28px;
          height: 28px;
          font-size: 14px;
          border-radius: 8px;
        }

        .brand-text {
          .navigation-title {
            font-size: 15px;
          }
          .navigation-sub-title {
            font-size: 9px;
          }
        }
      }

      .sidebar-sections {
        flex: 1;
        flex-direction: row;
        gap: 12px;
        overflow: hidden;

        .recent-wrapper,
        .setting-wrapper {
          flex: 1;

          .recent-title,
          .setting-title {
            display: none;
          }

          .reading-recent,
          .setting-item {
            margin: 0;
          }
        }
      }

      .bottom-icons {
        display: none;
      }
    }

    .shelf-wrapper {
      padding: 0;
      flex-grow: 1;

      :deep(.el-loading-spinner) {
        display: none;
      }
    }
  }
}

.night {
  .sidebar {
    background: var(--legado-sidebar-bg, #181825);
  }

  :deep(.shelf-wrapper) {
    background: var(--legado-bg, #11111b);
  }
}

.day {
  .sidebar {
    background: var(--legado-sidebar-bg, #1e1e2e);
  }
}
</style>
