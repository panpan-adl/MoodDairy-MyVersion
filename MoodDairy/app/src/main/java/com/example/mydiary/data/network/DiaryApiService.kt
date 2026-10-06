package com.example.mydiary.data.network

import com.example.mydiary.data.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API接口定义
 * 定义所有与后端通信的API端点
 * 
 * 验证需求: 2.4, 4.3, 8.1
 */
interface DiaryApiService {
    
    // ============================================================================
    // 日记相关API (Diary APIs)
    // ============================================================================
    
    /**
     * 创建日记
     * POST /diaries
     * 
     * 验证需求: 2.1, 2.4
     */
    @POST("diaries/")
    suspend fun createDiary(
        @Body request: CreateDiaryRequest
    ): Response<DiaryResponse>
    
    /**
     * 获取指定日期的日记
     * GET /diaries/{date}
     * 
     * 验证需求: 2.2, 2.4
     * 
     * @param date 日期字符串，格式为YYYY-MM-DD
     * @param userId 用户ID
     */
    @GET("diaries/{date}")
    suspend fun getDiaryByDate(
        @Path("date") date: String,
        @Query("user_id") userId: Long
    ): Response<DiaryResponse>
    
    /**
     * 更新日记
     * PUT /diaries/{diary_id}
     * 
     * 验证需求: 2.2, 2.4
     * 
     * @param diaryId 日记ID
     * @param request 更新请求数据
     */
    @PUT("diaries/{diary_id}")
    suspend fun updateDiary(
        @Path("diary_id") diaryId: Long,
        @Body request: UpdateDiaryRequest
    ): Response<DiaryResponse>
    
    /**
     * 获取有日记的日期列表
     * GET /diaries/dates-with-diary
     * 
     * 验证需求: 1.3
     * 
     * @param userId 用户ID
     * @param year 年份
     * @param month 月份 (1-12)
     */
    @GET("diaries/dates-with-diary")
    suspend fun getDatesWithDiary(
        @Query("user_id") userId: Long,
        @Query("year") year: Int,
        @Query("month") month: Int
    ): Response<DatesWithDiaryResponse>
    
    /**
     * 获取指定日期的所有日记
     * GET /diaries/{date}/all
     * 
     * 验证需求: 2.2, 2.4, 2.6
     * 用户需求Priority 1: 支持每天多条日记
     * 
     * @param date 日期字符串，格式为YYYY-MM-DD
     * @param userId 用户ID
     * @return 该日期的所有日记列表，按创建时间降序排列
     */
    @GET("diaries/{date}/all")
    suspend fun getDiariesByDate(
        @Path("date") date: String,
        @Query("user_id") userId: Long
    ): Response<List<DiaryResponse>>
    
    /**
     * 通过ID获取日记
     * GET /diaries/id/{diary_id}
     * 
     * 验证需求: 2.2, 2.4
     * 
     * @param diaryId 日记ID
     * @return 日记详情
     */
    @GET("diaries/id/{diary_id}")
    suspend fun getDiaryById(
        @Path("diary_id") diaryId: Long
    ): Response<DiaryResponse>
    
    /**
     * 批量同步媒体项（全量同步）
     * PUT /diaries/{diary_id}/media
     * 
     * 逻辑：
     * - 有 id 且属于该 diary → UPDATE
     * - 有 id 但不属于该 diary → 400 错误
     * - id = null → INSERT
     * - DB 有但请求没有 → DELETE
     * 
     * @param diaryId 日记ID
     * @param request 同步媒体请求（包含完整的媒体项列表）
     * @return 同步后的媒体项列表（按 sort_order 排序）
     */
    @PUT("diaries/{diary_id}/media")
    suspend fun syncDiaryMedia(
        @Path("diary_id") diaryId: Long,
        @Body request: SyncMediaRequest
    ): Response<List<MediaItemResponse>>
    
    /**
     * 删除日记
     * DELETE /diaries/{diary_id}
     * 
     * 级联删除关联的媒体项和语音转录
     * 
     * 验证需求: 用户需求Priority 2 - 日记删除功能
     * 
     * @param diaryId 日记ID
     * @param userId 用户ID（用于权限验证）
     */
    @DELETE("diaries/{diary_id}")
    suspend fun deleteDiary(
        @Path("diary_id") diaryId: Long,
        @Query("user_id") userId: Long
    ): Response<Unit>
    
    /**
     * 获取高光时刻日记列表
     * GET /diaries/highlights
     * 
     * @param userId 用户ID
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 高光时刻日记列表
     */
    @GET("diaries/highlights")
    suspend fun getHighlightDiaries(
        @Query("user_id") userId: Long,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<DiaryResponse>>
    
    /**
     * 获取小确幸日记列表
     * GET /diaries/little-joys
     * 
     * @param userId 用户ID
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 小确幸日记列表
     */
    @GET("diaries/little-joys")
    suspend fun getLittleJoyDiaries(
        @Query("user_id") userId: Long,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<DiaryResponse>>

    @GET("insights/growth-portrait")
    suspend fun getGrowthPortrait(
        @Query("days") days: Int = 30,
        @Query("end_date") endDate: String? = null
    ): Response<GrowthPortraitResponse>

    @GET("insights/diaries/{diary_id}")
    suspend fun getDiaryMultimodalInsight(
        @Path("diary_id") diaryId: Long
    ): Response<DiaryMultimodalInsightResponse>

    /**
     * 鏍规嵁鍏抽敭璇嶆绱㈡棩璁?
     * GET /diaries/search
     */
    @GET("diaries/search")
    suspend fun searchDiaries(
        @Query("user_id") userId: Long,
        @Query("query") query: String,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0
    ): Response<List<DiaryResponse>>

    // ============================================================================
    // 寰呭姙鐩稿叧API (Todo APIs)
    // ============================================================================

    /**
     * 鏌ヨ鎸囧畾鏃ユ湡寰呭姙
     * GET /todos
     */
    @GET("todos")
    suspend fun getTodosByDate(
        @Query("user_id") userId: Long,
        @Query("date") date: String
    ): Response<List<TodoResponse>>

    /**
     * 鍒涘缓寰呭姙
     * POST /todos
     */
    @POST("todos/")
    suspend fun createTodo(
        @Body request: CreateTodoRequest
    ): Response<TodoResponse>

    /**
     * 鏇存柊寰呭姙
     * PUT /todos/{todo_id}
     */
    @PUT("todos/{todo_id}")
    suspend fun updateTodo(
        @Path("todo_id") todoId: Long,
        @Query("user_id") userId: Long,
        @Body request: UpdateTodoRequest
    ): Response<TodoResponse>

    /**
     * 鍒犻櫎寰呭姙
     * DELETE /todos/{todo_id}
     */
    @DELETE("todos/{todo_id}")
    suspend fun deleteTodo(
        @Path("todo_id") todoId: Long,
        @Query("user_id") userId: Long
    ): Response<Unit>
    
    // ============================================================================
    // 媒体相关API (Media APIs)
    // ============================================================================
    
    /**
     * 上传媒体文件
     * POST /media/upload
     * 
     * 支持multipart/form-data格式上传
     * 返回文件URL和媒体ID
     * 
     * 验证需求: 4.3, 5.5, 6.3
     * 
     * @param file 上传的文件
     * @param diaryId 关联的日记ID
     * @param mediaType 媒体类型(image/audio/video)
     */
    @Multipart
    @POST("media/upload")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("diary_id") diaryId: RequestBody,
        @Part("media_type") mediaType: RequestBody
    ): Response<MediaUploadResponse>
    
    // ============================================================================
    // 语音处理相关API (Voice Processing APIs)
    // ============================================================================
    
    /**
     * 统一语音处理API
     * POST /voice/process
     * 
     * 执行完整的语音处理流程：
     * 1. 语音转文字
     * 2. 去除冗余词
     * 3. 情感分析
     * 
     * 验证需求: 8.1, 11.1, 11.2, 11.3
     * 
     * @param audio 音频文件
     * @param diaryId 关联的日记ID
     * @param mediaId 关联的媒体ID
     */
    @Multipart
    @POST("voice/process")
    suspend fun processVoice(
        @Part audio: MultipartBody.Part,
        @Part("diary_id") diaryId: RequestBody,
        @Part("media_id") mediaId: RequestBody
    ): Response<VoiceProcessingResult>
    
    /**
     * 带选项的语音处理API
     * POST /voice/process-with-options
     * 
     * 根据 save_mode 执行不同的语音处理流程：
     * - save_mode=1: 仅保存音频 + 语音情感分析
     * - save_mode=2: ASR + 去填充词 + LLM优化 + 语音情感分析（不保存音频媒体项）
     * - save_mode=3: 保存音频 + ASR + 去填充词 + LLM优化 + 语音情感分析
     * 
     * 验证需求: 1.2, 1.3, 1.4, 2.1, 3.1
     * 
     * @param audio 音频文件
     * @param diaryId 关联的日记ID
     * @param userId 用户ID
     * @param saveMode 保存模式(1=仅语音, 2=仅文字, 3=两者都保存)
     * @param mediaId 媒体ID（save_mode=1或3时需要）
     */
    @Multipart
    @POST("voice/process-with-options")
    suspend fun processVoiceWithOptions(
        @Part audio: MultipartBody.Part,
        @Part("diary_id") diaryId: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part("save_mode") saveMode: RequestBody,
        @Part("media_id") mediaId: RequestBody?
    ): Response<VoiceProcessWithOptionsResponse>
    
    /**
     * 仅语音转文字
     * POST /voice/transcribe
     * 
     * 验证需求: 8.1, 8.2, 8.3, 8.4
     * 
     * @param audio 音频文件
     */
    @Multipart
    @POST("voice/transcribe")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part
    ): Response<TranscriptionResponse>
    
    /**
     * 仅情感分析
     * POST /voice/analyze-emotion
     * 
     * 验证需求: 10.1, 10.2, 10.3, 10.4, 10.6
     * 
     * @param text 待分析的文本
     */
    @FormUrlEncoded
    @POST("voice/analyze-emotion")
    suspend fun analyzeEmotion(
        @Field("text") text: String
    ): Response<EmotionResult>
    
    // ============================================================================
    // 面部表情识别相关API (Face Emotion APIs)
    // ============================================================================

    /**
     * 上报摄像头表情识别结果
     * POST /face-emotion/report
     *
     * 聊天页前台静默采集的表情标签，经本地上报写入 emotion_records（source_type='face_camera'）
     *
     * @param request 表情标签、置信度与采集时间
     */
    @POST("face-emotion/report")
    suspend fun reportFaceEmotion(
        @Body request: FaceEmotionReportRequest
    ): Response<FaceEmotionReportResponse>

    /**
     * 按心情获取疗愈音乐推荐
     * GET /music/recommendations?mood=sad&limit=5
     */
    @GET("music/recommendations")
    suspend fun getMusicRecommendations(
        @Query("mood") mood: String,
        @Query("limit") limit: Int = 5
    ): Response<MusicRecommendationResponse>

    /**
     * 查询社交搜索会员开通状态（TikHub 真实余额）
     * GET /social-search/status
     */
    @GET("social-search/status")
    suspend fun getSocialSearchStatus(): Response<SocialSearchStatus>

    /**
     * 绑定当前用户自己的 TikHub API Key
     * POST /social-search/bind-key
     */
    @POST("social-search/bind-key")
    suspend fun bindSocialSearchKey(@Body body: BindTikHubKeyRequest): Response<SocialSearchStatus>

    /**
     * 解绑当前用户的 TikHub API Key
     * POST /social-search/unbind
     */
    @POST("social-search/unbind")
    suspend fun unbindSocialSearchKey(): Response<SocialSearchStatus>

    /**
     * 查询 ASR 转写状态
     * GET /voice/asr-status
     * 
     * 用于前端轮询 ASR 任务状态。
     * 建议轮询间隔：3-5秒，最多 60 次（约 5 分钟）。
     * 
     * @param taskId ASR 任务ID（从 process-with-options 返回）
     * @return ASR 状态响应
     */
    @GET("voice/asr-status")
    suspend fun getAsrStatus(
        @Query("task_id") taskId: String
    ): Response<AsrStatusResponse>
    
    // ============================================================================
    // 用户相关API (User APIs)
    // ============================================================================
    
    /**
     * 用户登录
     * POST /api/users/login
     * 
     * 验证需求: 1.1, 1.2
     * 
     * @param request 登录请求（用户名和密码）
     */
    @POST("api/users/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<UserResponse>
    
    /**
     * 用户注册
     * POST /api/users/register
     * 
     * 验证需求: 2.1, 2.2, 2.3
     * 
     * @param request 注册请求（用户名、密码、昵称）
     */
    @POST("api/users/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<UserResponse>
    
    /**
     * 获取用户信息
     * GET /api/users/{user_id}
     * 
     * 验证需求: 3.1, 3.2
     * 
     * @param userId 用户ID
     */
    @GET("api/users/{user_id}")
    suspend fun getUser(
        @Path("user_id") userId: Long
    ): Response<UserResponse>
    
    /**
     * 更新用户信息
     * PUT /api/users/{user_id}
     * 
     * 验证需求: 4.1, 4.2, 4.3
     * 
     * @param userId 用户ID
     * @param request 更新请求（昵称、手机、邮箱、性别、生日）
     */
    @PUT("api/users/{user_id}")
    suspend fun updateUser(
        @Path("user_id") userId: Long,
        @Body request: UpdateUserRequest
    ): Response<UserResponse>
    
    /**
     * 上传用户头像
     * POST /api/users/{user_id}/avatar
     * 
     * 验证需求: 5.1, 5.2, 5.3
     * 
     * @param userId 用户ID
     * @param file 头像文件
     */
    @Multipart
    @POST("api/users/{user_id}/avatar")
    suspend fun uploadAvatar(
        @Path("user_id") userId: Long,
        @Part file: MultipartBody.Part
    ): Response<AvatarUploadResponse>
    
    // ============================================================================
    // 聊天相关API (Chat APIs)
    // ============================================================================
    
    /**
     * 获取最近的日记摘要（供聊天使用）
     * GET /diaries/summaries
     * 
     * @param userId 用户ID
     * @param limit 返回数量（默认10）
     * @return 日记摘要列表（简化版）
     */
    @GET("diaries/summaries")
    suspend fun getRecentSummaries(
        @Query("user_id") userId: Long,
        @Query("limit") limit: Int = 10
    ): Response<List<DiarySummarySimple>>
    
    /**
     * 聊天接口
     * POST /chat
     * 
     * 发送消息给 AI，带日记摘要上下文
     * 
     * @param request 聊天请求（包含用户消息和日记摘要）
     * @return AI 回复
     */
    @POST("chat")
    suspend fun chat(
        @Body request: ChatRequest
    ): Response<ChatResponse>
    
    // ============================================================================
    // 画作生成相关API (Drawing APIs)
    // ============================================================================
    
    /**
     * 文字生成画作
     * POST /drawing/generate
     */
    @POST("drawing/generate")
    suspend fun generateDrawing(
        @Body request: DrawingGenerateRequest
    ): Response<DrawingResponse>
    
    /**
     * 简笔画增强
     * POST /drawing/enhance
     */
    @POST("drawing/enhance")
    suspend fun enhanceSketch(
        @Body request: DrawingEnhanceRequest
    ): Response<DrawingResponse>

    @Multipart
    @POST("drawing/sketch-upload")
    suspend fun uploadSketch(
        @Part file: MultipartBody.Part
    ): Response<SketchUploadResponse>
    
    /**
     * 从日记生成画作
     * POST /drawing/{diary_id}/from-diary
     */
    @POST("drawing/{diary_id}/from-diary")
    suspend fun generateFromDiary(
        @Path("diary_id") diaryId: Long,
        @Body request: DrawingFromDiaryRequest
    ): Response<DrawingResponse>
    
    // ============================================================================
    // 视频生成相关API (Video APIs)
    // ============================================================================
    
    /**
     * 画作生成视频
     * POST /drawing/video/create
     */
    @POST("drawing/video/create")
    suspend fun createVideo(
        @Body request: VideoGenerateRequest
    ): Response<VideoTaskResponse>
    
    /**
     * 情绪转换视频
     * POST /drawing/video/transform
     */
    @POST("drawing/video/transform")
    suspend fun createTransformVideo(
        @Body request: VideoTransformRequest
    ): Response<VideoTaskResponse>
    
    /**
     * 查询视频生成状态
     * GET /drawing/video/status/{task_id}
     */
    @GET("drawing/video/status/{task_id}")
    suspend fun getVideoStatus(
        @Path("task_id") taskId: String
    ): Response<VideoStatusResponse>

    // ============================================================================
    // SD 功能开关相关API (SD Toggle APIs)
    // ============================================================================

    /**
     * 获取 SD 功能开关状态
     * GET /drawing/sd-enabled
     *
     * @return SD 启用状态和当前模式信息
     */
    @GET("drawing/sd-enabled")
    suspend fun getSDEnabled(): Response<SDEnabledResponse>

    /**
     * 设置 SD 功能开关状态
     * POST /drawing/sd-enabled
     *
     * @param request 开关设置请求
     * @return 更新后的 SD 启用状态
     */
    @POST("drawing/sd-enabled")
    suspend fun setSDEnabled(
        @Body request: SDEnabledRequest
    ): Response<SDEnabledResponse>

    // ============================================================================
    // AI 绘图显示控制相关API (Drawing Toggle APIs)
    // ============================================================================

    /**
     * 获取 AI 绘图功能开关状态（用于控制移动端显示）
     * GET /drawing/enabled
     *
     * @return AI 绘图功能启用状态
     */
    @GET("drawing/enabled")
    suspend fun getDrawingEnabled(): Response<DrawingEnabledResponse>
}

/**
 * 扩展函数：简化聊天调用
 */
suspend fun DiaryApiService.sendChatMessage(
    userId: Long,
    message: String,
    conversationId: String,
    diarySummaries: List<DiarySummarySimple>,
    imageDataUrls: List<String> = emptyList()
): Response<ChatResponse> {
    return chat(
        ChatRequest(
            userId = userId,
            message = message,
            conversationId = conversationId,
            context = ChatContext(
                diarySummaries = diarySummaries,
                imageDataUrls = imageDataUrls
            )
        )
    )
}
