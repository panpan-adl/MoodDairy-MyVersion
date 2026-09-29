package com.example.mydiary.data.repository

import android.util.Log
import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiaryRepoDebug"

/**
 * 鏃ヨ鏁版嵁浠撳簱
 * 
 * 鑱岃矗锛? * - 灏佽缃戠粶璋冪敤鍜屾湰鍦扮紦瀛? * - 鎻愪緵缁熶竴鐨勬暟鎹闂帴鍙? * - 澶勭悊鏁版嵁杞崲锛堢綉缁滄ā鍨?<-> 棰嗗煙妯″瀷锛? * 
 * 楠岃瘉闇€姹? 2.1, 2.2, 2.4, 1.3
 */
@Singleton
class DiaryRepository @Inject constructor(
    private val apiService: DiaryApiService
) {
    
    // TODO: 鍚庣画鍙互娣诲姞鏈湴缂撳瓨锛圧oom鏁版嵁搴擄級
    // private val localDataSource: DiaryLocalDataSource
    
    /**
     * 鑾峰彇鎸囧畾鏃ユ湡鐨勬棩璁?     * 
     * 楠岃瘉闇€姹? 2.2, 2.4
     * 
     * @param userId 鐢ㄦ埛ID
     * @param date 鏃ユ湡
     * @return Flow<ApiResult<Diary>> 鏃ヨ鏁版嵁娴?     */
    fun getDiaryByDate(
        userId: Long,
        date: LocalDate
    ): Flow<ApiResult<Diary>> = flow {
        emit(ApiResult.Loading)
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.getDiaryByDate(
                date = date.toString(),
                userId = userId
            )
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                val diary = result.data.toDomain()
                emit(ApiResult.Success(diary))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鍒涘缓鏃ヨ
     * 
     * 楠岃瘉闇€姹? 2.1, 2.4
     * 
     * @param diary 鏃ヨ棰嗗煙妯″瀷
     * @return Flow<ApiResult<Diary>> 鍒涘缓鍚庣殑鏃ヨ鏁版嵁娴?     */
    fun createDiary(
        diary: Diary
    ): Flow<ApiResult<Diary>> = flow {
        emit(ApiResult.Loading)
        
        // 杞崲涓鸿姹傛ā鍨?
        val request = diary.toCreateRequest()
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.createDiary(request)
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                val createdDiary = result.data.toDomain()
                emit(ApiResult.Success(createdDiary))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鏇存柊鏃ヨ
     * 
     * 楠岃瘉闇€姹? 2.2, 2.4
     * 
     * @param diaryId 鏃ヨID
     * @param diary 鏇存柊鍚庣殑鏃ヨ棰嗗煙妯″瀷
     * @return Flow<ApiResult<Diary>> 鏇存柊鍚庣殑鏃ヨ鏁版嵁娴?     */
    fun updateDiary(
        diaryId: Long,
        diary: Diary
    ): Flow<ApiResult<Diary>> = flow {
        emit(ApiResult.Loading)
        
        // 杞崲涓鸿姹傛ā鍨?
        val request = diary.toUpdateRequest()
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.updateDiary(diaryId, request)
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                val updatedDiary = result.data.toDomain()
                emit(ApiResult.Success(updatedDiary))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鑾峰彇鎸囧畾鏈堜唤鏈夋棩璁扮殑鏃ユ湡鍒楄〃
     * 
     * 楠岃瘉闇€姹? 1.3
     * 
     * @param userId 鐢ㄦ埛ID
     * @param year 骞翠唤
     * @param month 鏈堜唤 (1-12)
     * @return Flow<ApiResult<List<LocalDate>>> 鏃ユ湡鍒楄〃鏁版嵁娴?     */
    fun getDatesWithDiary(
        userId: Long,
        year: Int,
        month: Int
    ): Flow<ApiResult<List<LocalDate>>> = flow {
        emit(ApiResult.Loading)
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.getDatesWithDiary(
                userId = userId,
                year = year,
                month = month
            )
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                // 灏嗘棩鏈熷瓧绗︿覆杞崲涓篖ocalDate鍒楄〃
                val dates = result.data.dates.map { dateString ->
                    LocalDate.parse(dateString)
                }
                emit(ApiResult.Success(dates))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鑾峰彇鎸囧畾鏃ユ湡鐨勬墍鏈夋棩璁?     * 
     * 楠岃瘉闇€姹? 2.2, 2.4, 2.6
     * 鐢ㄦ埛闇€姹侾riority 1: 鏀寔姣忓ぉ澶氭潯鏃ヨ
     * 
     * @param userId 鐢ㄦ埛ID
     * @param date 鏃ユ湡
     * @return Flow<ApiResult<List<Diary>>> 鏃ヨ鍒楄〃鏁版嵁娴侊紝鎸夊垱寤烘椂闂撮檷搴忔帓鍒?     */
    fun getAllDiariesByDate(
        userId: Long,
        date: LocalDate
    ): Flow<ApiResult<List<Diary>>> = flow {
        emit(ApiResult.Loading)
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.getDiariesByDate(
                date = date.toString(),
                userId = userId
            )
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                val diaries = result.data.map { it.toDomain() }
                emit(ApiResult.Success(diaries))
            }
            is ApiResult.Error -> {
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 閫氳繃ID鑾峰彇鏃ヨ
     * 
     * 楠岃瘉闇€姹? 2.2, 2.4
     * 
     * @param diaryId 鏃ヨID
     * @return Flow<ApiResult<Diary>> 鏃ヨ鏁版嵁娴?     */
    fun getDiaryById(
        diaryId: Long
    ): Flow<ApiResult<Diary>> = flow {
        Log.d(TAG, "=== getDiaryById($diaryId) 寮€濮?===")
        emit(ApiResult.Loading)
        
        // 璋冪敤API
        Log.d(TAG, "璋冪敤 API: GET /diaries/id/$diaryId")
        val result = SafeApiCall.execute {
            apiService.getDiaryById(diaryId)
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                Log.d(TAG, "=== API璋冪敤鎴愬姛 ===")
                Log.d(TAG, "鍝嶅簲鏁版嵁 ID: ${result.data.id}")
                Log.d(TAG, "鍝嶅簲鏁版嵁 鏍囬: ${result.data.title}")
                Log.d(TAG, "鍝嶅簲鏁版嵁 鏃ユ湡: ${result.data.diaryDate}")
                Log.d(TAG, "鍝嶅簲鏁版嵁 濯掍綋椤规暟閲? ${result.data.mediaItems.size}")
                
                val diary = result.data.toDomain()
                Log.d(TAG, "杞崲鍚?Diary ID: ${diary.id}")
                Log.d(TAG, "杞崲鍚?Diary 鏍囬: ${diary.title}")
                Log.d(TAG, "杞崲鍚?Diary 濯掍綋椤规暟閲? ${diary.mediaItems.size}")
                
                emit(ApiResult.Success(diary))
            }
            is ApiResult.Error -> {
                Log.e(TAG, "=== API璋冪敤澶辫触 ===")
                Log.e(TAG, "閿欒鐮? ${result.code}")
                Log.e(TAG, "閿欒淇℃伅: ${result.message}")
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鎵归噺鍚屾濯掍綋椤癸紙鍏ㄩ噺鍚屾锛?     * 
     * 閫昏緫锛?     * - 瑙勮寖鍖栭『搴忥紙sortedBy + mapIndexed锛?     * - 杞崲涓鸿姹傛ā鍨?     * - 璋冪敤 API
     * - 杩斿洖鍚屾鍚庣殑濯掍綋椤瑰垪琛紙鐢ㄤ簬鍥炲啓 UIState锛?     * 
     * @param diaryId 鏃ヨID
     * @param mediaItems 濯掍綋椤瑰垪琛紙鍙互涓虹┖锛岀敤浜庡垹闄ゆ墍鏈夊獟浣擄級
     * @return Flow<ApiResult<List<MediaItem>>> 鍚屾鍚庣殑濯掍綋椤瑰垪琛?     */
    fun syncMediaItems(
        diaryId: Long,
        mediaItems: List<MediaItem>
    ): Flow<ApiResult<List<MediaItem>>> = flow {
        Log.d(TAG, "=== syncMediaItems($diaryId) 寮€濮?===")
        Log.d(TAG, "濯掍綋椤规暟閲? ${mediaItems.size}")
        emit(ApiResult.Loading)
        
        // 瑙勮寖鍖栭『搴忥細sortedBy + mapIndexed
        val normalizedItems = mediaItems
            .sortedBy { it.order }
            .mapIndexed { index, item -> item.withOrder(index) }
        
        Log.d(TAG, "瑙勮寖鍖栧悗濯掍綋椤规暟閲? ${normalizedItems.size}")
        
        // 杞崲涓鸿姹傛ā鍨?
        val request = normalizedItems.toSyncRequest()
        
        Log.d(TAG, "璋冪敤 API: PUT /diaries/$diaryId/media")
        
        // 璋冪敤 API
        val result = SafeApiCall.execute {
            apiService.syncDiaryMedia(diaryId, request)
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                Log.d(TAG, "=== 鍚屾鎴愬姛 ===")
                Log.d(TAG, "杩斿洖濯掍綋椤规暟閲? ${result.data.size}")
                
                // 杞崲涓洪鍩熸ā鍨?
                val syncedMediaItems = result.data.map { it.toDomain() }
                emit(ApiResult.Success(syncedMediaItems))
            }
            is ApiResult.Error -> {
                Log.e(TAG, "=== 鍚屾澶辫触 ===")
                Log.e(TAG, "閿欒鐮? ${result.code}")
                Log.e(TAG, "閿欒淇℃伅: ${result.message}")
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    /**
     * 鍒犻櫎鏃ヨ
     * 
     * 楠岃瘉闇€姹? 鐢ㄦ埛闇€姹侾riority 2 - 鏃ヨ鍒犻櫎鍔熻兘
     * 
     * @param diaryId 鏃ヨID
     * @param userId 鐢ㄦ埛ID锛堢敤浜庢潈闄愰獙璇侊級
     * @return Flow<ApiResult<Unit>> 鍒犻櫎缁撴灉
     */
    fun deleteDiary(
        diaryId: Long,
        userId: Long
    ): Flow<ApiResult<Unit>> = flow {
        Log.d(TAG, "=== deleteDiary($diaryId, $userId) 寮€濮?===")
        emit(ApiResult.Loading)
        
        // 璋冪敤API
        val result = SafeApiCall.execute {
            apiService.deleteDiary(diaryId, userId)
        }
        
        // 杞崲缁撴灉
        when (result) {
            is ApiResult.Success -> {
                Log.d(TAG, "=== 鍒犻櫎鎴愬姛 ===")
                emit(ApiResult.Success(Unit))
            }
            is ApiResult.Error -> {
                Log.e(TAG, "=== 鍒犻櫎澶辫触 ===")
                Log.e(TAG, "閿欒鐮? ${result.code}")
                Log.e(TAG, "閿欒淇℃伅: ${result.message}")
                emit(result)
            }
            is ApiResult.Loading -> {
                // 涓嶅簲璇ュ埌杈捐繖閲?
                }
        }
    }
    
    // ============================================================================
    // 鏈湴缂撳瓨鐩稿叧鏂规硶锛堝緟瀹炵幇锛?    // ============================================================================
    
    /**
     * 浠庢湰鍦扮紦瀛樿幏鍙栨棩璁?     * TODO: 瀹炵幇鏈湴缂撳瓨閫昏緫
     * 
     * 楠岃瘉闇€姹? 2.3
     */
    
    /**
     * 鏂囧瓧鍏抽敭璇嶆绱㈡棩璁?
     */
    fun searchDiaries(
        userId: Long,
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): Flow<ApiResult<List<Diary>>> = flow {
        emit(ApiResult.Loading)

        val result = SafeApiCall.execute {
            apiService.searchDiaries(
                userId = userId,
                query = query,
                limit = limit,
                offset = offset
            )
        }

        when (result) {
            is ApiResult.Success -> {
                val diaries = result.data.map { it.toDomain() }
                emit(ApiResult.Success(diaries))
            }
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }
    private suspend fun getDiaryFromCache(date: LocalDate): Diary? {
        // TODO: 浠嶳oom鏁版嵁搴撹鍙?
        return null
    }
    
    /**
     * 淇濆瓨鏃ヨ鍒版湰鍦扮紦瀛?     * TODO: 瀹炵幇鏈湴缂撳瓨閫昏緫
     * 
     * 楠岃瘉闇€姹? 2.3
     */
    private suspend fun saveDiaryToCache(diary: Diary) {
        // TODO: 淇濆瓨鍒癛oom鏁版嵁搴?
        }
    
    /**
     * 娓呴櫎鏈湴缂撳瓨
     * TODO: 瀹炵幇鏈湴缂撳瓨閫昏緫
     */
    private suspend fun clearCache() {
        // TODO: 娓呴櫎Room鏁版嵁搴?
        }
}

