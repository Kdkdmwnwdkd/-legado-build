package com.shimmer.reader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shimmer.reader.data.entities.BookAiChapterSummary

@Dao
interface BookAiChapterSummaryDao {

    @Query("SELECT * FROM book_ai_chapter_summaries WHERE cacheKey = :cacheKey LIMIT 1")
    fun get(cacheKey: String): BookAiChapterSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(summary: BookAiChapterSummary)

    @Query("DELETE FROM book_ai_chapter_summaries WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
