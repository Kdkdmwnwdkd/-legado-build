<template>
  <div class="books-wrapper">
    <div class="wrapper">
      <div
        class="book-card"
        v-for="book in books"
        :key="book.bookUrl"
        @click="handleClick(book)"
      >
        <div class="cover-wrap">
          <img
            class="cover"
            :src="getCover(book)"
            :key="book.coverUrl"
            @error.once="proxyImage($event, book)"
            alt=""
            loading="lazy"
          />
        </div>
        <div class="info">
          <div class="name">{{ book.name }}</div>
          <div class="meta-row">
            <span class="author">{{ book.author }}</span>
            <span class="dot">·</span>
            <span class="size">{{ (book as Book).totalChapterNum }}章</span>
            <span class="dot">·</span>
            <span class="date">{{ dateFormat((book as Book).lastCheckTime) }}</span>
          </div>
          <div class="chapter-line">
            <span class="chapter-label">已读</span>
            <span class="chapter-text">{{ (book as Book).durChapterTitle }}</span>
          </div>
          <div class="chapter-line">
            <span class="chapter-label">最新</span>
            <span class="chapter-text">{{ book.latestChapterTitle }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import type { Book } from '@/book'
import { dateFormat, isLegadoUrl } from '../utils/utils'
import API from '@api'
const props = defineProps<{
  books: Book[]
}>()

const emit = defineEmits(['bookClick'])
const handleClick = (book: Book) => emit('bookClick', book)
const getCover = ({ bookUrl, coverUrl }: Book) => {
  if (coverUrl === undefined || isLegadoUrl(coverUrl)) {
    return API.getBookCoverUrl(bookUrl)
  }
  return coverUrl
}
const proxyImage = (evt: Event, book: Book) => {
  const target = evt.target as HTMLImageElement
  target.src = API.getBookCoverUrl(book.bookUrl)
}

</script>

<style lang="scss" scoped>
.books-wrapper {
  overflow: auto;
  height: 100%;

  .wrapper {
    display: grid;
    grid-template-columns: repeat(auto-fill, 340px);
    justify-content: start;
    gap: 12px;
    padding-bottom: 20px;

    .book-card {
      user-select: none;
      display: flex;
      cursor: pointer;
      width: 340px;
      padding: 16px;
      border-radius: var(--legado-radius, 14px);
      background: var(--legado-surface, #fff);
      border: 1px solid var(--legado-border, rgba(0, 0, 0, 0.06));
      box-shadow: var(--legado-shadow, 0 1px 3px rgba(0, 0, 0, 0.04));
      transition: all var(--legado-transition, 0.28s cubic-bezier(0.4, 0, 0.2, 1));

      .cover-wrap {
        width: 72px;
        height: 96px;
        border-radius: var(--legado-radius-sm, 10px);
        overflow: hidden;
        flex-shrink: 0;
        background: var(--legado-surface-hover, #f7f8fa);

        .cover {
          width: 72px;
          height: 96px;
          border-radius: var(--legado-radius-sm, 10px);
          object-fit: cover;
        }
      }

      .info {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-left: 16px;
        flex: 1;
        overflow: hidden;
        justify-content: center;

        .name {
          font-size: 14px;
          font-weight: 600;
          color: var(--legado-text, #1e1e2e);
          line-height: 1.3;
          overflow: hidden;
          text-overflow: ellipsis;
          display: -webkit-box;
          -webkit-box-orient: vertical;
          -webkit-line-clamp: 1;
          line-clamp: 1;
        }

        .meta-row {
          display: flex;
          align-items: center;
          gap: 4px;
          font-size: 11px;
          color: var(--legado-text-faint, #a0a3b8);
          font-weight: 400;

          .dot {
            opacity: 0.5;
          }
        }

        .chapter-line {
          display: flex;
          align-items: baseline;
          gap: 6px;
          font-size: 11px;
          overflow: hidden;

          .chapter-label {
            color: var(--legado-accent, #89b4fa);
            font-weight: 600;
            font-size: 10px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            flex-shrink: 0;
            width: 28px;
          }

          .chapter-text {
            color: var(--legado-text-dim, #6c6f85);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            flex: 1;
          }
        }
      }
    }

    .book-card:hover {
      background: var(--legado-surface-hover, #f7f8fa);
      box-shadow: var(--legado-shadow-lg, 0 4px 24px rgba(0, 0, 0, 0.08));
      transform: translateY(-3px);
      border-color: var(--legado-border-strong, rgba(0, 0, 0, 0.1));
    }
  }
}

.books-wrapper::-webkit-scrollbar {
  width: 6px !important;
}
.books-wrapper::-webkit-scrollbar-track {
  background: transparent;
}
.books-wrapper::-webkit-scrollbar-thumb {
  background: var(--legado-border-strong, rgba(0, 0, 0, 0.1));
  border-radius: 3px;
}

@media screen and (max-width: 750px) {
  .books-wrapper {
    .wrapper {
      display: flex;
      flex-direction: column;
      gap: 0;

      .book-card {
        width: 100%;
        padding: 14px 18px;
        border-radius: 0;
        border: none;
        border-bottom: 1px solid var(--legado-border, rgba(0, 0, 0, 0.06));
        box-shadow: none;

        &:hover {
          transform: none;
          background: var(--legado-surface-hover, #f7f8fa);
          box-shadow: none;
        }
      }
    }
  }
}
</style>
