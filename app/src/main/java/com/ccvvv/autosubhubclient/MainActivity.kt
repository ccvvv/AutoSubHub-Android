package com.ccvvv.autosubhubclient

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.ccvvv.autosubhubclient.storage.LocalArtifactCleaner
import com.ccvvv.autosubhubclient.storage.TaskStore
import com.ccvvv.autosubhubclient.backend.BackendConfig
import com.ccvvv.autosubhubclient.backend.GitHubReadOnlyTransport
import com.ccvvv.autosubhubclient.backend.SshDeployKeyManager
import com.ccvvv.autosubhubclient.backend.SshReceiptUploader
import com.ccvvv.autosubhubclient.runtime.MihomoCoreManager
import com.ccvvv.autosubhubclient.runtime.PhoneProbeEngine

class MainActivity : Activity() {
    private var runtimeLogAppender: ((String) -> Unit)? = null
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val taskStore by lazy { TaskStore(applicationContext) }
    private val preferences by lazy {
        getSharedPreferences("client_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(padding, padding * 2, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "AutoSubHub Android"
            textSize = 25f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "独立客户端 · 首版工程骨架"
            textSize = 14f
            setPadding(0, padding / 3, 0, padding)
        }
        root.addView(subtitle)

        val backendLabel = TextView(this).apply {
            text = "后端仓库地址"
            textSize = 16f
        }
        root.addView(backendLabel)

        val backendInput = EditText(this).apply {
            hint = "https://github.com/owner/repository"
            setSingleLine(true)
            setText(preferences.getString(KEY_BACKEND_URL, "https://github.com/ccvvv/AutoSubHub"))
        }
        root.addView(backendInput)

        val backendStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 3, 0, padding / 3)
            text = "尚未检查后端连接"
        }
        val saveButton = Button(this).apply {
            text = "保存后端配置"
            setOnClickListener {
                try {
                    val config = BackendConfig(
                        repositoryUrl = backendInput.text.toString().trim(),
                    )
                    config.validate()
                    preferences.edit()
                        .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                        .apply()
                    backendStatus.text = "配置已保存；尚未连接"
                    Toast.makeText(this@MainActivity, "后端配置已保存在本机", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(this@MainActivity, error.message ?: "后端配置无效", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(saveButton)
        root.addView(backendStatus)
        val connectButton = Button(this).apply {
            text = "检查 GitHub 后端连接"
            val button = this
            setOnClickListener {
                val config = try {
                    BackendConfig(
                        repositoryUrl = backendInput.text.toString().trim(),
                    ).also { it.validate() }
                } catch (error: Exception) {
                    backendStatus.text = "配置错误：${error.message}"
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                    .apply()
                button.isEnabled = false
                backendStatus.text = "正在读取 GitHub 回执（后台执行）…"
                ioExecutor.execute {
                    try {
                        val latest = GitHubReadOnlyTransport(config).readLatestReceipt()
                        val message = if (latest.receiptCommit == null) {
                            "仓库连接成功；当前数据分支尚无最新回执"
                        } else {
                            "仓库连接成功\n最新回执状态：${latest.status ?: "未知"}\n候选版本：${latest.candidateCommit ?: "未知"}\n回执标识：${latest.receiptId?.take(12) ?: "未知"}\n远端回执提交：${latest.receiptCommit}"
                        }
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                backendStatus.text = message
                                button.isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                backendStatus.text = "连接失败：${error.message ?: error.javaClass.simpleName}"
                                button.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(connectButton)
        val mihomoLog = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding / 2, padding / 2, padding / 2, padding / 2)
            text = "运行日志：等待操作"
        }
        val mihomoLogScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(mihomoLog)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        }
        root.addView(mihomoLogScroll)
        runtimeLogAppender = { message ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    val existing = mihomoLog.text.toString()
                    mihomoLog.text = (if (existing == "运行日志：等待操作") "" else "$existing\n") +
                        "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $message"
                    mihomoLogScroll.post { mihomoLogScroll.fullScroll(ViewGroup.FOCUS_DOWN) }
                }
            }
        }
        fun appendMihomoLog(message: String) = runtimeLogAppender?.invoke(message)

        val downloadQueueButton = Button(this).apply {
            text = "下载待测池快照"
            val button = this
            setOnClickListener {
                val config = try {
                    BackendConfig(
                        repositoryUrl = backendInput.text.toString().trim(),
                    ).also { it.validate() }
                } catch (error: Exception) {
                    backendStatus.text = "配置错误：${error.message}"
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                    .apply()
                button.isEnabled = false
                backendStatus.text = "正在下载并校验待测池快照…"
                ioExecutor.execute {
                    try {
                        val candidate = GitHubReadOnlyTransport(config).fetchCandidateSubscription(
                            File(filesDir, "candidate-subscriptions")
                        )
                        val message = "待测池快照下载并校验成功" +
                            "\n待测池节点数：${candidate.candidateCount}" +
                            "\n文件大小：${candidate.byteCount} 字节" +
                            "\n候选提交：${candidate.candidateCommit}" +
                            "\nSHA256：${candidate.candidateSha256}" +
                            "\n本地文件：${candidate.candidateFile.name} + manifest.json"
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                backendStatus.text = message
                                button.isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                val detail = android.util.Log.getStackTraceString(error)
                        runtimeLogAppender?.invoke("下载待测池快照失败，完整异常堆栈：\n$detail")
                        backendStatus.text = "下载待测池快照失败：${error.message ?: error.javaClass.simpleName}\n完整堆栈已写入运行日志。"
                                button.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(downloadQueueButton)
        val mihomoStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 3, 0, padding / 3)
            text = "Mihomo 核心尚未验证"
        }
        root.addView(mihomoStatus)



        root.addView(Button(this).apply {
            text = "校验候选订阅的 Mihomo 配置"
            setOnClickListener {
                isEnabled = false
                mihomoStatus.text = "正在校验待测池快照配置…"
                appendMihomoLog("开始待测池快照配置校验")
                ioExecutor.execute {
                    try {
                        val workspace = File(filesDir, "candidate-subscriptions")
                        val candidateFile = workspace.listFiles()
                            ?.filter { it.isDirectory && it.name.startsWith("candidate-") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.firstOrNull()
                            ?.let { File(it, "subscription.yaml") }
                            ?: throw IllegalStateException("请先点击“下载待测池快照”")
                        appendMihomoLog("使用本地候选文件：${candidateFile.name}")
                        val result = MihomoCoreManager(applicationContext)
                            .validateCandidateSubscription(candidateFile)
                        appendMihomoLog("候选配置校验结果：$result")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = result
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        val detail = android.util.Log.getStackTraceString(error)
                        appendMihomoLog("候选配置校验失败：$detail")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = "候选配置校验失败：${error.message ?: error.javaClass.simpleName}"
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        })

        root.addView(Button(this).apply {
            text = "复制运行日志"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mihomo 运行日志", mihomoLog.text))
                Toast.makeText(this@MainActivity, "日志已复制", Toast.LENGTH_SHORT).show()
            }
        })

        val mihomoTestButton = Button(this).apply {
            text = "下载并验证 Mihomo 核心"
            setOnClickListener {
                isEnabled = false
                mihomoStatus.text = "正在验证 Mihomo 核心…"
                appendMihomoLog("开始核心安装/版本验证")
                ioExecutor.execute {
                    try {
                        appendMihomoLog("检查核心文件；必要时下载官方 ARM64 核心并校验 SHA-256")
                        val result = MihomoCoreManager(applicationContext).startVersionCheck()
                        appendMihomoLog("版本检查输出：$result")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = "核心验证成功：$result"
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        val detail = android.util.Log.getStackTraceString(error)
                        appendMihomoLog("核心验证失败：$detail")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = "核心验证失败：${error.message ?: error.javaClass.simpleName}"
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(mihomoTestButton)
        val mihomoSmokeButton = Button(this).apply {
            text = "测试核心加载配置并启动/停止"
            setOnClickListener {
                isEnabled = false
                mihomoStatus.text = "正在校验最小配置并测试启动…"
                appendMihomoLog("开始最小配置校验与核心启动测试")
                ioExecutor.execute {
                    try {
                        appendMihomoLog("生成本地 smoke-test.yaml，校验配置")
                        val result = MihomoCoreManager(applicationContext).testMinimalConfig()
                        appendMihomoLog("测试结果：$result")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = "核心启动测试通过：$result"
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        val detail = android.util.Log.getStackTraceString(error)
                        appendMihomoLog("核心启动测试失败：$detail")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mihomoStatus.text = "核心启动测试失败：${error.message ?: error.javaClass.simpleName}"
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(mihomoSmokeButton)
        val sshHeading = TextView(this).apply {
            text = "GitHub SSH 部署密钥"
            textSize = 16f
            setPadding(0, padding, 0, padding / 3)
        }
        root.addView(sshHeading)
        val sshStatus = TextView(this).apply {
            textSize = 13f
            text = "尚未读取密钥。生成后将私钥保存在 App 私有目录，只展示可添加到 GitHub 的公钥。"
        }
        root.addView(sshStatus)
        val sshPublicKey = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding / 2, padding / 2, padding / 2, padding / 2)
            text = "公钥尚未生成"
        }
        root.addView(sshPublicKey)
        var currentSshPublicKey = ""
        root.addView(Button(this).apply {
            text = "生成 / 读取 SSH 密钥"
            setOnClickListener {
                isEnabled = false
                sshStatus.text = "正在安全生成或读取 RSA 3072 SSH 密钥…"
                ioExecutor.execute {
                    try {
                        val info = SshDeployKeyManager(filesDir).getOrCreate()
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                currentSshPublicKey = info.publicKey
                                sshPublicKey.text = info.publicKey
                                sshStatus.text = "密钥已就绪。指纹：${info.fingerprint}\n私钥保存在 App 私有目录，不会显示或上传。"
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                sshStatus.text = "SSH 密钥生成失败：${error.message ?: error.javaClass.simpleName}"
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "复制 SSH 公钥"
            setOnClickListener {
                if (currentSshPublicKey.isBlank()) {
                    Toast.makeText(this@MainActivity, "请先生成或读取 SSH 密钥", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AutoSubHub GitHub deploy key", currentSshPublicKey))
                    Toast.makeText(this@MainActivity, "公钥已复制；请添加到 AutoSubHub 仓库 Deploy keys", Toast.LENGTH_LONG).show()
                }
            }
        })
        addSection(root, "GitHub 添加位置",
            "打开 ccvvv/AutoSubHub 仓库 → Settings → Deploy keys → Add deploy key。粘贴上面的公钥；如需 App 推送测速回执，必须勾选 Allow write access。不要上传或分享私钥。")
        addSection(root, "任务状态", "空闲 · 后续接入持久化后台任务")
        val concurrencyLabel = TextView(this).apply {
            text = "测速并发（1～300）"
            textSize = 16f
            setPadding(0, padding, 0, padding / 3)
        }
        root.addView(concurrencyLabel)
        val concurrencyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "默认 200"
            setText(preferences.getInt(KEY_PROBE_CONCURRENCY, DEFAULT_PROBE_CONCURRENCY).toString())
        }
        root.addView(concurrencyInput)
        val concurrencyStatus = TextView(this).apply {
            textSize = 12f
            text = "当前设置：" + preferences.getInt(KEY_PROBE_CONCURRENCY, DEFAULT_PROBE_CONCURRENCY)
        }
        root.addView(concurrencyStatus)
        root.addView(Button(this).apply {
            text = "保存并发设置"
            setOnClickListener {
                val value = concurrencyInput.text.toString().toIntOrNull()
                if (value == null || value !in MIN_PROBE_CONCURRENCY..MAX_PROBE_CONCURRENCY) {
                    concurrencyStatus.text = "请输入 1～300 之间的整数"
                    return@setOnClickListener
                }
                preferences.edit().putInt(KEY_PROBE_CONCURRENCY, value).apply()
                concurrencyStatus.text = "已保存：" + value + " 个并发任务（新任务读取此设置）"
                Toast.makeText(this@MainActivity, "并发设置已保存", Toast.LENGTH_SHORT).show()
            }
        })

        val probeStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 2, 0, padding / 3)
            text = "正式测速：尚未开始。将执行五站验证、六项流媒体检测和 5 MiB 下载测速。"
        }
        root.addView(probeStatus)
        val formalProbeButton = Button(this).apply {
            text = "开始正式测速（生成本地回执）"
            setOnClickListener {
                val config = try {
                    BackendConfig(repositoryUrl = backendInput.text.toString().trim()).also { it.validate() }
                } catch (error: Exception) {
                    probeStatus.text = "后端配置错误：${error.message}"
                    return@setOnClickListener
                }
                val workers = preferences.getInt(KEY_PROBE_CONCURRENCY, DEFAULT_PROBE_CONCURRENCY)
                    .coerceIn(MIN_PROBE_CONCURRENCY, MAX_PROBE_CONCURRENCY)
                isEnabled = false
                probeStatus.text = "正在准备正式测速；并发 $workers"
                appendMihomoLog("开始正式测速：并发 $workers；五站 + 六项流媒体 + 5 MiB 下载")
                ioExecutor.execute {
                    try {
                        val transport = GitHubReadOnlyTransport(config)
                        val candidate = transport.fetchCandidateSubscription(File(filesDir, "candidate-subscriptions"))
                        appendMihomoLog("待测池快照已核验：${candidate.candidateCount} 个节点，提交 ${candidate.candidateCommit}")
                        val outcome = PhoneProbeEngine(MihomoCoreManager(applicationContext), filesDir).run(candidate, workers) { progress ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) probeStatus.text = progress
                            }
                        }
                        val message = "正式测速完成：${outcome.tested}/${outcome.total}\n" +
                            "通过：${outcome.passed}（高速 ${outcome.high}，普通 ${outcome.normal}）\n" +
                            "回执状态：${outcome.status}\n任务：${outcome.taskId}\n本地回执：${outcome.receiptFile.absolutePath}\n" +
                            "回执已保存；尚未上传。"
                        appendMihomoLog(message)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                probeStatus.text = message
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        val detail = android.util.Log.getStackTraceString(error)
                        appendMihomoLog("正式测速失败：$detail")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                probeStatus.text = "正式测速失败：${error.message ?: error.javaClass.simpleName}"
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(formalProbeButton)
        root.addView(Button(this).apply {
            text = "查看本地测速回执数量"
            setOnClickListener {
                val receipts = File(filesDir, "receipts").listFiles()
                    ?.filter { it.isDirectory && File(it, "receipt.json").isFile }.orEmpty()
                probeStatus.text = "本地已保存 ${receipts.size} 份测速回执。上传功能独立接入中；此按钮不会重新测速或删除回执。"
            }
        })

        root.addView(Button(this).apply {
            text = "上传一份已保存测速回执（SSH）"
            setOnClickListener {
                val config = try {
                    BackendConfig(repositoryUrl = backendInput.text.toString().trim()).also { it.validate() }
                } catch (error: Exception) {
                    probeStatus.text = "后端配置错误：${error.message}"
                    return@setOnClickListener
                }
                val pending = File(filesDir, "receipts").listFiles()
                    ?.filter { it.isDirectory && File(it, "receipt.json").isFile }
                    ?.sortedBy { it.lastModified() }.orEmpty()
                val receipt = pending.firstOrNull()?.let { File(it, "receipt.json") }
                if (receipt == null) {
                    probeStatus.text = "没有待上传的本地测速回执。"
                    return@setOnClickListener
                }
                isEnabled = false
                probeStatus.text = "正在通过 SSH 上传回执；不会重新测速…"
                ioExecutor.execute {
                    try {
                        val packageInfo = packageManager.getPackageInfo(packageName, 0)
                        appendMihomoLog(
                            "UPLOAD_TRACE build=264 versionName=${packageInfo.versionName} " +
                                "versionCode=${packageInfo.longVersionCode} package=$packageName " +
                                "receipt=${receipt.name} uploader=manifest-validation"
                        )
                        val result = SshReceiptUploader(config, filesDir).upload(receipt)
                        val remaining = File(filesDir, "receipts").listFiles()
                            ?.count { it.isDirectory && File(it, "receipt.json").isFile } ?: 0
                        val message = "SSH 上传并远端核验成功。\n回执：${result.receiptId}\n远端提交：${result.commit}\n" +
                            "已删除本地回执及两个快照文件（${result.deletedLocalFiles} 个）。\n剩余待上传回执：$remaining"
                        appendMihomoLog(message)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                probeStatus.text = message
                                isEnabled = true
                            }
                        }
                    } catch (error: Exception) {
                        // Keep the concise status visible, but persist the complete stack trace
                        // so runtime/library IndexOutOfBoundsException failures can be located.
                        val detail = android.util.Log.getStackTraceString(error)
                        appendMihomoLog("SSH 上传异常完整堆栈：\n$detail")
                        val message = "SSH 上传失败，本地回执完整保留：${error.message ?: error.javaClass.simpleName}\n详细异常堆栈已写入运行日志。"
                        appendMihomoLog(message)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                probeStatus.text = message
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        })

        val storageSummary = taskStore.getStorageSummary()
        val storageStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 2, 0, padding / 3)
            text = buildStorageText(storageSummary)
        }
        root.addView(TextView(this).apply {
            text = "本地文件管理"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        })
        root.addView(storageStatus)

        val cleanupButton = Button(this).apply {
            text = if (storageSummary.cleanupEligibleCount > 0) {
                "清理已确认上传的文件"
            } else {
                "暂无可安全清理的文件"
            }
            isEnabled = storageSummary.cleanupEligibleCount > 0
            setOnClickListener {
                isEnabled = false
                ioExecutor.execute {
                    val result = LocalArtifactCleaner(this@MainActivity, taskStore)
                        .cleanConfirmedArtifacts()
                    val updated = taskStore.getStorageSummary()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        storageStatus.text = buildStorageText(updated)
                        text = if (updated.cleanupEligibleCount > 0) {
                            "清理剩余已确认文件"
                        } else {
                            "暂无可安全清理的文件"
                        }
                        isEnabled = updated.cleanupEligibleCount > 0
                        val message = "已清理 ${result.deletedCount} 个文件，释放 ${formatBytes(result.deletedBytes)}；保留 ${result.preservedCount} 个"
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(cleanupButton)

        val footer = TextView(this).apply {
            text = "安全提示：私钥和访问令牌不会写入仓库。"
            textSize = 12f
            setPadding(0, padding, 0, 0)
        }
        root.addView(footer)

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(pageScroll)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildStorageText(summary: com.ccvvv.autosubhubclient.storage.StorageSummary): String =
        "已登记 ${summary.registeredArtifactCount} 个文件（${formatBytes(summary.registeredBytes)}）\n" +
            "远端确认后可清理：${summary.cleanupEligibleCount} 个（${formatBytes(summary.cleanupEligibleBytes)}）\n" +
            "保留本地副本：${summary.keepLocalCount} 个"

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private fun addSection(root: LinearLayout, heading: String, detail: String) {
        val padding = (resources.displayMetrics.density * 12).toInt()
        val title = TextView(this).apply {
            text = heading
            textSize = 16f
            setPadding(0, padding, 0, 0)
        }
        val description = TextView(this).apply {
            text = detail
            textSize = 13f
        }
        root.addView(title)
        root.addView(description)
    }

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_PROBE_CONCURRENCY = "probe_concurrency"
        private const val DEFAULT_PROBE_CONCURRENCY = 200
        private const val MIN_PROBE_CONCURRENCY = 1
        private const val MAX_PROBE_CONCURRENCY = 300
    }
}
