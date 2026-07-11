package com.mobile.meplayer.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobile.meplayer.controller.AppSettings
import com.mobile.meplayer.controller.SettingsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * IPTV 源管理 ViewModel，对应 Flutter 的 `IptvSourcesController`。
 *
 * 源数据直接存放在 [AppSettings.controller] 的 `iptvSources` 中，通过它读写持久化。
 * 本类只负责：暴露源列表 + 增删改 + 远程源刷新（仅触发一次 GET 校验可达性）。
 *
 * 刷新只是验证链接可用并提示，真正的 M3U 内容加载发生在播放页
 * [IptvPlayerViewModel.loadSource] 中。
 */
class IptvSourcesViewModel : ViewModel() {

    private val ctrl: SettingsController = AppSettings.controller
    private val client = OkHttpClient()

    /** 源列表，直接转发自全局 [SettingsController]。 */
    val sources: StateFlow<List<Map<String, String>>> = ctrl.iptvSources

    /** 各源是否正在刷新，key = 源在列表中的 index。 */
    val isRefreshing = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    fun addSource(source: Map<String, String>) {
        ctrl.addIptvSource(source)
    }

    fun updateSource(index: Int, source: Map<String, String>) {
        ctrl.updateIptvSource(index, source)
    }

    fun removeSource(index: Int) {
        ctrl.removeIptvSource(index)
    }

    /**
     * 刷新远程源（仅触发一次 GET，实际加载在播放页 `loadSource` 中完成）。
     * 结果通过 [onResult] 回调：(success, message)。
     */
    fun refreshSource(index: Int, onResult: (Boolean, String) -> Unit) {
        val list = ctrl.iptvSources.value
        if (index !in list.indices) {
            onResult(false, "源不存在")
            return
        }
        val src = list[index]
        val url = src["url"] ?: ""
        if (url.isEmpty()) {
            onResult(false, "URL 为空")
            return
        }

        setRefreshing(index, true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException("HTTP ${resp.code}")
                        }
                        resp.body?.string()
                    }
                }
                onResult(true, "已刷新：${src["name"] ?: ""}")
            } catch (e: Exception) {
                onResult(false, "刷新失败：${e.message ?: ""}")
            } finally {
                setRefreshing(index, false)
            }
        }
    }

    private fun setRefreshing(index: Int, value: Boolean) {
        isRefreshing.value = isRefreshing.value.toMutableMap().apply {
            if (value) this[index] = true else this.remove(index)
        }
    }
}
