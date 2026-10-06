package com.example.mydiary.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vipDataStore by preferencesDataStore(name = "vip_prefs")

/**
 * 会员（VIP）状态持久化管理。
 *
 * 用于模拟充值解锁「全网内容搜索」等高级功能。
 * 充值状态保存在 DataStore，重启 App 后仍然有效。
 */
@Singleton
class VipPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_VIP_UNLOCKED = booleanPreferencesKey("vip_unlocked")
        val KEY_PACKAGE_NAME = stringPreferencesKey("vip_package_name")
        val KEY_EXPIRE_AT = longPreferencesKey("vip_expire_at")
        val KEY_RECHARGE_TIME = longPreferencesKey("vip_recharge_time")
    }

    /** 是否已开通会员 */
    val isVip: Flow<Boolean> = context.vipDataStore.data.map { prefs ->
        prefs[KEY_VIP_UNLOCKED] ?: false
    }

    /** 套餐名称（如「月度会员」） */
    val packageName: Flow<String?> = context.vipDataStore.data.map { prefs ->
        prefs[KEY_PACKAGE_NAME]
    }

    /** 到期时间戳（毫秒） */
    val expireAt: Flow<Long?> = context.vipDataStore.data.map { prefs ->
        prefs[KEY_EXPIRE_AT]
    }

    /**
     * 模拟充值成功，开通会员。
     * @param packageName 套餐名称
     * @param durationDays 会员有效天数
     */
    suspend fun activateVip(packageName: String, durationDays: Int) {
        val now = System.currentTimeMillis()
        val expireAt = now + durationDays * 24L * 60 * 60 * 1000
        context.vipDataStore.edit { prefs ->
            prefs[KEY_VIP_UNLOCKED] = true
            prefs[KEY_PACKAGE_NAME] = packageName
            prefs[KEY_EXPIRE_AT] = expireAt
            prefs[KEY_RECHARGE_TIME] = now
        }
    }

    /** 仅用于演示：重置会员状态 */
    suspend fun resetVip() {
        context.vipDataStore.edit { prefs ->
            prefs[KEY_VIP_UNLOCKED] = false
            prefs.remove(KEY_PACKAGE_NAME)
            prefs.remove(KEY_EXPIRE_AT)
            prefs.remove(KEY_RECHARGE_TIME)
        }
    }
}
