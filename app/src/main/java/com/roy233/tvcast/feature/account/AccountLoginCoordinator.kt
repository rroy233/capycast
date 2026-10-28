package com.roy233.tvcast.feature.account

import android.content.Context
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.account.model.AccountAuthService
import com.roy233.tvcast.feature.account.model.AccountNavigator
import com.roy233.tvcast.feature.account.model.AccountSessionStore
import com.roy233.tvcast.feature.account.model.Poll
import com.roy233.tvcast.feature.account.model.Token
import com.roy233.tvcast.platform.qr.QrCodeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 协调电视端二维码登录流程，不负责 Compose 界面渲染。 */
class AccountLoginCoordinator(
    /** 用于调用登录接口的应用上下文。 */
    private val context: Context,
    /** 执行登录协程的作用域。 */
    private val scope: CoroutineScope,
    /** 读取账号功能独占的状态切片。 */
    private val readState: () -> AccountUiState,
    /** 写入账号功能独占的状态切片。 */
    private val writeState: (AccountUiState) -> Unit,
    /** 登录令牌存储。 */
    private val sessionStore: AccountSessionStore,
    /** 二维码登录服务。 */
    private val authService: AccountAuthService,
    private val accountNavigator: AccountNavigator,
    /** 二维码生成器。 */
    private val qrCodeGenerator: QrCodeGenerator,
    /** 登录令牌可用时的回调。 */
    private val onTokenAvailable: (Token) -> Unit,
    /** 退出登录时的回调。 */
    private val onLogout: () -> Unit,
    /** 退出登录后的导航和续播状态更新。 */
    private val onLogoutUi: () -> Unit,
    /** 显示账号功能提示。 */
    private val onToast: (String) -> Unit,
) {

    /** 当前二维码登录任务。 */
    private var loginJob: Job? = null

    /** 读取已有令牌或启动新的二维码登录流程。 */
    fun startOrLogin() {
        val token = sessionStore.load()
        LogHub.i(TAG, "startOrLogin token=${token != null}")
        if (token != null) {
            onTokenAvailable(token)
            LogHub.setLogin(token.mid)
            update {
                it.copy(
                    loggedIn = true,
                    mid = token.mid,
                    accountName = "已登录",
                    loginQr = null,
                    loginStatus = "",
                    loginSkipped = false,
                )
            }
            fetchNav()
            return
        }
        update {
            it.copy(
                loggedIn = false,
                accountName = "未登录",
                loginQr = null,
                loginStatus = "正在获取二维码…",
                loginSkipped = false,
            )
        }
        startQrLogin()
    }

    /** 启动二维码申请和状态轮询任务。 */
    fun startQrLogin() {
        if (loginJob?.isActive == true) return
        update {
            it.copy(
                loginQr = null,
                loginStatus = "正在获取二维码…",
                loginSkipped = false,
            )
        }
        loginJob = scope.launch {
            while (isActive) {
                val qr = withContext(Dispatchers.IO) {
                    runCatching { authService.requestQr(context) }
                }
                if (qr.isFailure) {
                    LogHub.e(TAG, "qr request failed: ${qr.exceptionOrNull()?.message}")
                    update { it.copy(loginStatus = "获取二维码失败，正在重试…") }
                    delay(4000)
                    continue
                }

                val code = qr.getOrThrow()
                val bitmap = qrCodeGenerator.generate(code.url)
                LogHub.setQrUrl(code.url)
                LogHub.setQrPending(true)
                update {
                    it.copy(
                        loginQr = bitmap,
                        loginStatus = if (bitmap == null) "二维码生成失败，正在重试…" else "等待扫码…",
                    )
                }
                if (bitmap == null) {
                    delay(2000)
                    continue
                }

                var age = 0
                var expire = false
                var lastPending = Int.MIN_VALUE
                while (isActive && !expire && age < 170) {
                    delay(2000)
                    age += 2
                    val poll = withContext(Dispatchers.IO) {
                        runCatching { authService.poll(context, code.authCode, code.sessionId) }
                    }.getOrNull()

                    when (poll) {
                        is Poll.Success -> {
                            sessionStore.save(poll.token)
                            onTokenAvailable(poll.token)
                            LogHub.setLogin(poll.token.mid)
                            LogHub.setQrPending(false)
                            LogHub.setQrUrl("")
                            LogHub.i(TAG, "login ok mid=${poll.token.mid}")
                            update {
                                it.copy(
                                    loggedIn = true,
                                    mid = poll.token.mid,
                                    accountName = "已登录",
                                    loginQr = null,
                                    loginStatus = "",
                                    loginSkipped = false,
                                )
                            }
                            fetchNav()
                            return@launch
                        }

                        is Poll.Pending -> {
                            if (poll.code != lastPending) {
                                lastPending = poll.code
                                LogHub.i(TAG, "qr poll ${poll.code} ${poll.message}")
                            }
                            when (poll.code) {
                                86039 -> update { it.copy(loginStatus = "等待扫码…") }
                                86038 -> {
                                    update {
                                        it.copy(
                                            loginQr = null,
                                            loginStatus = "二维码已失效，正在刷新…",
                                        )
                                    }
                                    expire = true
                                }

                                else -> update { it.copy(loginStatus = "等待扫码…") }
                            }
                        }

                        null -> update { it.copy(loginStatus = "网络异常，重试中…") }
                    }
                }
                if (expire) delay(1000)
            }
        }
    }

    /** 取消登录流程并进入访客状态。 */
    fun skipLogin() {
        LogHub.i(TAG, "skip login (guest)")
        LogHub.setQrPending(false)
        LogHub.setQrUrl("")
        loginJob?.cancel()
        loginJob = null
        update {
            it.copy(
                loginQr = null,
                loginStatus = "",
                loginSkipped = true,
                loggedIn = false,
                accountName = "未登录",
            )
        }
    }

    /** 清除登录凭据并重新进入登录准备状态。 */
    fun logout() {
        loginJob?.cancel()
        loginJob = null
        sessionStore.clear()
        onLogout()
        LogHub.setLogin(0L)
        LogHub.setQrPending(false)
        LogHub.setQrUrl("")
        update {
            it.copy(
                loggedIn = false,
                mid = 0L,
                accountName = "未登录",
                vip = false,
                loginQr = null,
                loginStatus = "",
                loginSkipped = false,
            )
        }
        onLogoutUi()
        onToast("已退出登录")
        startQrLogin()
    }

    /** 取消当前登录协程。 */
    fun cancel() {
        loginJob?.cancel()
        loginJob = null
    }

    /** 异步读取账号导航信息并更新界面状态。 */
    private fun fetchNav() {
        scope.launch {
            val nav = withContext(Dispatchers.IO) {
                runCatching { accountNavigator.currentProfile() }.getOrNull()
            }
            if (nav != null) {
                update {
                    it.copy(
                        accountName = nav.name.ifBlank { "已登录" },
                        vip = nav.vip,
                        mid = if (nav.mid > 0) nav.mid else it.mid,
                    )
                }
            }
        }
    }

    /** 使用转换函数更新当前界面状态。 */
    private fun update(transform: (AccountUiState) -> AccountUiState) {
        writeState(transform(readState()))
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
