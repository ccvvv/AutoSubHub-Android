package com.ccvvv.autosubhubclient

import android.app.Activity
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
import com.ccvvv.autosubhubclient.runtime.RuntimeEnvironment
import com.ccvvv.autosubhubclient.runtime.RuntimeExecutionProbe
import com.ccvvv.autosubhubclient.backend.BackendConfig
import com.ccvvv.autosubhubclient.backend.GitHubReadOnlyTransport

class MainActivity : Activity() {
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

        val branchLabel = TextView(this).apply {
            text = "数据分支"
            textSize = 16f
            setPadding(0, padding / 2, 0, 0)
        }
        root.addView(branchLabel)
        val branchInput = EditText(this).apply {
            hint = "published-subscriptions"
            setSingleLine(true)
            setText(preferences.getString(KEY_DATA_BRANCH, "published-subscriptions"))
        }
        root.addView(branchInput)

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
                        dataBranch = branchInput.text.toString().trim()
                    )
                    config.validate()
                    preferences.edit()
                        .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                        .putString(KEY_DATA_BRANCH, config.dataBranch)
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
                        dataBranch = branchInput.text.toString().trim()
                    ).also { it.validate() }
                } catch (error: Exception) {
                    backendStatus.text = "配置错误：${error.message}"
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                    .putString(KEY_DATA_BRANCH, config.dataBranch)
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
        val downloadQueueButton = Button(this).apply {
            text = "下载原始待测速订阅"
            val button = this
            setOnClickListener {
                val config = try {
                    BackendConfig(
                        repositoryUrl = backendInput.text.toString().trim(),
                        dataBranch = branchInput.text.toString().trim()
                    ).also { it.validate() }
                } catch (error: Exception) {
                    backendStatus.text = "配置错误：${error.message}"
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(KEY_BACKEND_URL, config.repositoryUrl.trimEnd('/'))
                    .putString(KEY_DATA_BRANCH, config.dataBranch)
                    .apply()
                button.isEnabled = false
                backendStatus.text = "正在下载 AutoSubHub 原始候选订阅…"
                ioExecutor.execute {
                    try {
                        val candidate = GitHubReadOnlyTransport(config).fetchCandidateSubscription(
                            File(filesDir, "candidate-subscriptions")
                        )
                        val message = "原始候选订阅下载并校验成功" +
                            "\n待测节点：${candidate.candidateCount}" +
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
                                backendStatus.text = "下载云端待测速清单失败：${error.message ?: error.javaClass.simpleName}"
                                button.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        root.addView(downloadQueueButton)
        val runtimeStatus = TextView(this).apply {
            textSize = 13f
            text = RuntimeEnvironment.inspect(this@MainActivity).summary() +
                "\n基础探针已随 APK 打包；测速运行环境仍在集成中"
        }
        root.addView(TextView(this).apply {
            text = "运行环境"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        })
        root.addView(runtimeStatus)
        root.addView(Button(this).apply {
            text = "执行 ELF 自检（无害测试程序）"
            setOnClickListener {
                isEnabled = false
                ioExecutor.execute {
                    val result = RuntimeExecutionProbe(this@MainActivity).runSelfTest()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            runtimeStatus.text = RuntimeEnvironment.inspect(
                                this@MainActivity,
                                elfExecutionVerified = result.success
                            ).summary() + "\n" + result.detail
                            isEnabled = true
                            Toast.makeText(
                                this@MainActivity,
                                if (result.success) "ELF 执行成功" else "ELF 执行未通过：" + result.detail,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
        addSection(root, "SSH 密钥", "尚未生成 · 后续加入本地密钥生成与公钥展示")
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
            hint = "默认 50"
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

        setContentView(root)
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
        private const val KEY_DATA_BRANCH = "data_branch"
        private const val KEY_PROBE_CONCURRENCY = "probe_concurrency"
        private const val DEFAULT_PROBE_CONCURRENCY = 50
        private const val MIN_PROBE_CONCURRENCY = 1
        private const val MAX_PROBE_CONCURRENCY = 300
    }
}
