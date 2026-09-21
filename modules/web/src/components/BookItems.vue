<template>
  <div class="books-wrapper">
    <div class="wrapper">
      <div
        class="book"
        v-for="book in books"
        :key="book.bookUrl"
        @click="handleClick(book)"
      >
        <div class="cover-img">
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
          <div class="sub">
            <div class="author">
              {{ book.author }}
            </div>
            <div class="update-info">
              <div class="dot">•</div>
              <div class="size">共{{ (book as Book).totalChapterNum }}章</div>
              <div class="dot">•</div>
              <div class="date">
                {{ dateFormat((book as Book).lastCheckTime) }}
              </div>
            </div>
          </div>
          <div class="dur-chapter">
            已读：{{ (book as Book).durChapterTitle }}
          </div>
          <div class="last-chapter">最新：{{ book.latestChapterTitle }}</div>
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

  .wrapper {
    display: grid;
    grid-template-columns: repeat(auto-fill, 380px);
    justify-content: space-around;
    grid-gap: 10px;

    .book {
      user-select: none;
      display: flex;
      cursor: pointer;
      margin-bottom: 18px;
      padding: 24px 24px;
      width: 360px;
      flex-direction: row;
      justify-content: space-around;

      .cover-img {
        width: 84px;
        height: 112px;

        .cover {
          width: 84px;
          height: 112px;
        }
      }

      .info {
        display: flex;
        flex-direction: column;
        justify-content: space-around;
        align-items: left;
        height: 112px;
        margin-left: 20px;
        flex: 1;
        overflow: hidden;

        .name {
          width: fit-content;
          font-size: 16px;
          font-weight: 700;
          color: #33373d;
        }

        .sub {
          display: flex;
          flex-direction: row;
          align-items: baseline;
          justify-content: flex-start;
          font-size: 12px;
          font-weight: 600;
          color: #6b6b6b;
          .update-info {
            display: flex;
            .dot {
              margin: 0 7px;
            }
          }
        }

        .dur-chapter,
        .last-chapter {
          color: #969ba3;
          font-size: 13px;
          margin-top: 3px;
          font-weight: 500;
          word-wrap: break-word;
          overflow: hidden;
          text-overflow: ellipsis;
          display: -webkit-box;
          -webkit-box-orient: vertical;
          -webkit-line-clamp: 1;
          line-clamp: 1;
          text-align: left;
        }
      }
    }

    .book:hover {
      background: rgba(0, 0, 0, 0.1);
      transition-duration: 0.5s;
    }
  }

  .wrapper:last-child {
    margin-right: auto;
  }
}

.books-wrapper::-webkit-scrollbar {
  width: 0 !important;
}

@media screen and (max-width: 750px) {
  .books-wrapper {
    .wrapper {
      display: flex;
      flex-direction: column;

      .book {
        box-sizing: border-box;
        width: 100%;
        margin-bottom: 0;
        padding: 10px 20px;
      }
    }
  }
}
</style>
