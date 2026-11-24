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
import com.ccvvv.autosubhubclient.storage.LocalArtifactCleaner
import com.ccvvv.autosubhubclient.storage.TaskStore

class MainActivity : Activity() {
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

        val saveButton = Button(this).apply {
            text = "保存仓库地址"
            setOnClickListener {
                val value = backendInput.text.toString().trim()
                val path = value.removePrefix("https://github.com/").trim('/')
                val valid = value.startsWith("https://github.com/") &&
                    path.split('/').size == 2 &&
                    path.none { it.isWhitespace() }
                if (!valid) {
                    Toast.makeText(
                        this@MainActivity,
                        "请输入有效的 GitHub 仓库地址",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    preferences.edit().putString(KEY_BACKEND_URL, value.trimEnd('/')).apply()
                    Toast.makeText(this@MainActivity, "仓库地址已保存在本机", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(saveButton)

        addSection(root, "运行环境", "尚未安装 · 当前版本不包含可执行运行时")
        addSection(root, "SSH 密钥", "尚未生成 · 后续加入本地密钥生成与公钥展示")
        addSection(root, "任务状态", "空闲 · 后续接入持久化后台任务")
        addSection(root, "测速并发", "目标上限 300 · 必须通过真机压力测试后启用")

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
                Thread {
                    val result = LocalArtifactCleaner(this@MainActivity, taskStore)
                        .cleanConfirmedArtifacts()
                    val updated = taskStore.getStorageSummary()
                    runOnUiThread {
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
                }.start()
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

    private fun buildStorageText(summary: com.ccvvv.autosubhubclient.storage.StorageSummary): String =
        "已登记 ${summary.registeredArtifactCount} 个文件（${formatBytes(summary.registeredBytes)}）\\n" +
            "远端确认后可清理：${summary.cleanupEligibleCount} 个（${formatBytes(summary.cleanupEligibleBytes)}）\\n" +
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
    }
}
