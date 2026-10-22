package com.example.mydiary

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mydiary.memory.MemoryRoom
import com.example.mydiary.memory.MemoryStore
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.models.User
import com.example.mydiary.ui.theme.MyDiaryTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import com.example.mydiary.memory.MemoryActivity
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import com.example.mydiary.memory.MemorySessionExpired
import com.example.mydiary.data.network.ApiEndpoints
import kotlinx.coroutines.runBlocking

class MemorySkillsInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private fun restoreActiveSession(session:SessionManager,user:User?,token:String?) {
        if(user==null||token==null) {session.clearSession(); return}
        val valid=runCatching {
            val request=okhttp3.Request.Builder().url(ApiEndpoints.apiBaseUrl+"api/users/${user.id}").header("Authorization","Bearer $token").build()
            okhttp3.OkHttpClient.Builder().callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use {r ->
                when(r.code) {
                    401,403,404 -> false
                    200 -> JSONObject(r.body!!.string()).let{it.optString("username")==user.username&&it.optInt("status",1)==1}
                    else -> true // An unavailable server is not evidence of an invalid account.
                }
            }
        }.getOrDefault(true)
        if(valid) session.saveSession(user,token) else session.clearSession()
    }

    @Test fun roomItemsRemainClickableAcrossAllThemes() {
        var clicked=false
        for(theme in listOf("study","bedroom","cabin")) {
            compose.activityRule.scenario.onActivity {activity -> activity.setContent {
                MyDiaryTheme {MemoryRoom(theme,listOf(JSONObject().put("slot",0).put("kind","book").put("title","一次小进步"))) {clicked=true}}
            }}
            compose.onNodeWithText("一次小进步").performClick()
            assertTrue(clicked); clicked=false
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null),"memory-room-qa.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }

    @Test fun offlineDraftAndMediaSurviveReopenAndStayAccountScoped() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=SessionManager(context); val previous=session.getSession(); val token=session.getAccessToken()
        fun account(id:Long)=User(id=id,username="qa-memory",nickname=null,avatar=null,phone=null,email=null,gender=0,birthday=null,status=1,createdAt=null,updatedAt=null)
        try {
            session.saveSession(account(91000001),"qa-only-no-network")
            val a=MemoryStore(context,session); val request=UUID.randomUUID().toString()
            a.save(JSONObject().put("request_id",request).put("title","断网草稿").put("text","这是只属于测试账号的文字"))
            val name=a.writeMedia("private-recording".toByteArray())
            val raw=File(context.noBackupFilesDir,"memory/91000001/$name").readBytes()
            assertFalse(raw.contentEquals("private-recording".toByteArray()))
            assertEquals("断网草稿",MemoryStore(context,session).draft()!!.getString("title"))
            assertEquals("private-recording",String(a.readMedia(name)))
            session.saveSession(account(91000002),"qa-only-no-network")
            assertNull(MemoryStore(context,session).draft())
            session.saveSession(account(91000001),"qa-only-no-network")
            a.deleteMedia(name)
            val prefs=com.example.mydiary.data.local.SecurePreferences.create(context,"memory_drafts_secure")
            prefs.edit().remove("draft_91000001").remove("saved_91000001_$request").commit()
        } finally {
            if(previous!=null&&token!=null) session.saveSession(previous,token) else session.clearSession()
        }
    }

    @Test fun nativeCreateRenderAndRoomWithDisposableAccount() {
        val arguments=InstrumentationRegistry.getArguments()
        val qaToken=arguments.getString("memory_qa_token")
        org.junit.Assume.assumeTrue(qaToken!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=SessionManager(context); val previous=session.getSession(); val oldToken=session.getAccessToken()
        val qaUser=User(id=arguments.getString("memory_qa_user")!!.toLong(),username="qa-memory-native",nickname=null,avatar=null,phone=null,email=null,gender=0,birthday=null,status=1,createdAt=null,updatedAt=null)
        session.saveSession(qaUser,qaToken!!)
        var activity:ActivityScenario<MemoryActivity>?=null
        try {
            val networkStore=MemoryStore(context,session)
            runBlocking {
                assertTrue(networkStore.api("future-letters") is org.json.JSONArray)
                // Cross the backend's five-second keep-alive expiry, then refresh all
                // three resources through the same client and the real adb tunnel.
                repeat(2) {
                    kotlinx.coroutines.delay(6500)
                    assertTrue(networkStore.api("future-letters") is org.json.JSONArray)
                    assertTrue(networkStore.api("emotion-space") is JSONObject)
                    assertTrue(networkStore.api("memory-materials") is org.json.JSONArray)
                }
            }
            activity=ActivityScenario.launch(Intent(context,MemoryActivity::class.java))
            compose.waitUntil(30000) { compose.onAllNodesWithText("写一封视频信").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30000) { runCatching {compose.onNodeWithText("写一封视频信").assertIsEnabled(); true}.getOrDefault(false) }
            compose.onNodeWithText("写一封视频信").performClick()
            compose.onNodeWithTag("letter-body").performScrollTo().performTextInput("这是一封用于测试的信。愿你记得今天的努力。")
            activity.onActivity { screen ->
                screen.currentFocus?.clearFocus()
                (screen.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(screen.window.decorView.windowToken, 0)
            }
            compose.waitForIdle()
            compose.onNodeWithText("保存到账号").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("已保存到账号").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("生成视频并预览").performScrollTo().performClick()
            compose.waitUntil(90000) { compose.onAllNodesWithText("确认封存").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("确认封存").assertIsEnabled()
            runBlocking {
                val letter=(networkStore.api("future-letters") as org.json.JSONArray).getJSONObject(0)
                val video=networkStore.bytes("future-letters/${letter.getString("id")}/play")
                assertTrue(video.size>1000)
                assertEquals("ftyp",String(video,4,4,Charsets.US_ASCII))
            }
            compose.onNodeWithText("情绪空间").performScrollTo().performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithText("自然小屋").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30000) { runCatching { compose.onNodeWithText("放入一段经历").assertIsEnabled(); true }.getOrDefault(false) }
            // The shelf and the collection list intentionally share the title.
            compose.onAllNodesWithText("测试的记忆").onFirst().performClick()
            compose.onNodeWithText("仅用于手机端验收的虚构日记。").assertExists()
            compose.onNodeWithText("关闭").performClick()
            compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap -> File(context.getExternalFilesDir(null),"memory-native-screen-qa.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        } catch (failure: Throwable) {
            runCatching { compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "memory-native-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } }
            throw failure
        } finally {
            activity?.close()
            val store=MemoryStore(context,session); for(d in store.drafts()) store.discard(d)
            restoreActiveSession(session,previous,oldToken)
        }
    }

    @Test fun deletedAccountIsAnAuthFailureAndKeepsDraft() {
        val args=InstrumentationRegistry.getArguments(); val token=args.getString("memory_qa_deleted_token")
        org.junit.Assume.assumeTrue(token!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=SessionManager(context); val previous=session.getSession(); val oldToken=session.getAccessToken()
        val ghost=User(id=922337200000000001L,username="qa-deleted-account",nickname=null,avatar=null,phone=null,email=null,gender=0,birthday=null,status=1,createdAt=null,updatedAt=null)
        session.saveSession(ghost,token!!); val store=MemoryStore(context,session)
        val local=JSONObject().put("request_id",UUID.randomUUID().toString()).put("title","保留的草稿")
        try {
            store.save(local)
            var expired=false
            try {runBlocking {store.api("emotion-space")}} catch(_:MemorySessionExpired) {expired=true}
            assertTrue(expired); assertEquals("保留的草稿",store.draft()!!.getString("title"))
        } finally {store.discard(local); restoreActiveSession(session,previous,oldToken)}
    }
}
