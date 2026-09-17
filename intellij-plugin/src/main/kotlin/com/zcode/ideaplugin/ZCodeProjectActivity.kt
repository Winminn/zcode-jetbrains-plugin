package com.zcode.ideaplugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zcode.ideaplugin.protocol.V1BuiltinMigrator
import com.zcode.ideaplugin.protocol.ZCodeLocator
import com.zcode.ideaplugin.ui.ZCodeAutoArchiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目启动活动：预热自动归档调度器（缺陷BH）。
 *
 * ZCodeAutoArchiveService 是懒加载 Service，30min 扫描周期挂在 init——此前只有打开
 * 历史「自动归档」tab（webview 拉 config/records）才创建，IDE 重启后不碰该 tab，
 * 自动归档就静默不跑、「最近扫描」停留在上一个会话。这里项目打开即 touch 一行，
 * 让调度器随项目启动（开销：单守护线程 + 一行日志，无 IO）。
 *
 * 扫描本体「搭便车」于已运行的 app-server（客户端未起跳过，不主动拉起进程），
 * 客户端就绪场景由 [ZCodeAutoArchiveService.sweepAfterClientReady] 在 15s 内补扫。
 */
class ZCodeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ZCodeAutoArchiveService.getInstance(project)
        withContext(Dispatchers.IO) { migrateV1BuiltinsOnce(project) }
    }

    companion object {
        private val log = Logger.getInstance(ZCodeProjectActivity::class.java)

        /** 已迁 templateId 标记（PropertiesComponent 应用级）：防「迁过被用户删除再复活」 */
        private const val MARKER_KEY = "zcode.v1builtin.migrated"
    }

    /**
     * v1 内置渠道兜底迁移（一次性，2026-09-17 用户需求）：NEW 代 + provider_config.json
     * 未配置对应模板渠道 + v1 config.json 里该渠道带明文 apiKey → 按模板形态迁入
     * （BigModel/Z.ai Coding Plan 与 API Key 手填型，详见 [V1BuiltinMigrator]）。
     *
     * 后台线程 fail-soft（任意异常只落日志，不阻塞启动）；迁入成功记录标记并弹 IDE
     * 气泡提示。标记在迁移判定**之前**传入跳过集——用户事后在 v2 删除的渠道不复活。
     */
    private fun migrateV1BuiltinsOnce(project: Project) {
        try {
            val pc = PropertiesComponent.getInstance()
            val done = pc.getValue(MARKER_KEY)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val zcodePath = try {
                ZCodeLocator.detect()
            } catch (_: Exception) {
                null
            }
            val migrated = V1BuiltinMigrator.migrateIfNeeded(zcodePath, skipTemplateIds = done)
            if (migrated.isEmpty()) return
            pc.setValue(MARKER_KEY, (done + migrated).joinToString(","))
            log.info("v1 builtin channels migrated to provider_config.json: $migrated")
            NotificationGroupManager.getInstance().getNotificationGroup("ZCode")
                .createNotification(
                    ZCodeBundle.message("migrate.v1builtin.title"),
                    ZCodeBundle.message("migrate.v1builtin.body", migrated.joinToString("、")),
                    NotificationType.INFORMATION,
                )
                .notify(project)
        } catch (e: Exception) {
            log.warn("v1 builtin migration failed (ignored): ${e.message}")
        }
    }
}
