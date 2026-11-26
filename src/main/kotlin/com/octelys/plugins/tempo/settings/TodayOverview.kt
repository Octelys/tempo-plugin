package com.octelys.plugins.tempo.settings

import com.octelys.plugins.tempo.api.AtlassianApiException
import com.octelys.plugins.tempo.auth.ConfigurationMissingException
import com.octelys.plugins.tempo.core.DayOverview
import com.octelys.plugins.tempo.core.Durations
import com.octelys.plugins.tempo.ledger.WorklogLedger
import com.octelys.plugins.tempo.submission.DayWorklog
import com.octelys.plugins.tempo.submission.WorklogSubmitter
import com.octelys.plugins.tempo.tracking.ActivityMonitor
import com.octelys.plugins.tempo.tracking.ProjectActivityTracker
import com.octelys.plugins.tempo.tracking.TempoListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/**
 * The day at a glance on the settings page: per issue, the time this plugin sent to Tempo and the
 * time still pending, and everything logged in Tempo today. Read-only.
 */
internal class TodayOverview(parent: Disposable) {
    private val trackedModel = ReadOnlyModel("Issue", "Day", "Sent to Tempo", "Pending")
    private val tempoModel = ReadOnlyModel("Start", "Issue", "Duration", "Description", "Logged by")

    val trackedTable: JComponent = tableOf(trackedModel, "Nothing tracked today")
    val tempoTable: JComponent = tableOf(tempoModel, "Nothing logged in Tempo today")
    val trackedTotal = JBLabel()
    val tempoStatus = TempoConfigurable.StatusLabel()

    @Volatile
    private var disposed = false
    private val expired = Condition<Any?> { disposed }

    init {
        val disposable = Disposer.newDisposable(parent, "Tempo today overview")
        Disposer.register(disposable) { disposed = true }
        ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
            TempoListener.TOPIC,
            TempoListener { ApplicationManager.getApplication().invokeLater(::refreshTracked, ModalityState.any(), expired) },
        )
    }

    /** Re-reads the ledger, including the sessions still being tracked. */
    fun refreshTracked() {
        ActivityMonitor.openProjects().forEach { ProjectActivityTracker.getInstance(it).persistOpenSession() }
        val ledger = WorklogLedger.getInstance()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lines = DayOverview.of(today, ledger.pending(), ledger.open(), ledger.submitted(), zone)

        trackedModel.replaceRows(lines.map { listOf(it.issueKey, dayLabel(it.date, today), seconds(it.sentSeconds), seconds(it.pendingSeconds)) })
        trackedTotal.text = if (lines.isEmpty()) "" else
            "Total: ${Durations.format(lines.sumOf { it.sentSeconds })} sent · ${Durations.format(lines.sumOf { it.pendingSeconds })} pending"
    }

    /** Asks Tempo for today's worklogs in the background. */
    fun refreshTempo() {
        tempoStatus.busy("Loading today's worklogs from Tempo…")
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val result = runCatching { WorklogSubmitter.getInstance().loggedOn(LocalDate.now()) }
            application.invokeLater({ showTempo(result) }, ModalityState.any(), expired)
        }
    }

    private fun showTempo(result: Result<List<DayWorklog>>) {
        result.onSuccess { worklogs ->
            tempoModel.replaceRows(worklogs.map {
                listOf(it.startTime.format(TIME), it.issueKey ?: "—", Durations.format(it.seconds), it.description, if (it.byPlugin) "This plugin" else "Other")
            })
            tempoStatus.ok("${Durations.format(worklogs.sumOf { it.seconds })} logged in Tempo today")
        }.onFailure { error ->
            tempoModel.replaceRows(emptyList())
            when {
                error is ConfigurationMissingException -> tempoStatus.warning(error.message.orEmpty())
                error is AtlassianApiException && (error.statusCode == 401 || error.statusCode == 403) ->
                    tempoStatus.warning("Not allowed to read your Tempo worklogs: ${error.message}")
                else -> {
                    thisLogger().warn("Could not load today's Tempo worklogs", error)
                    tempoStatus.error("Could not load today's worklogs: ${error.message ?: error}")
                }
            }
        }
    }

    private class ReadOnlyModel(vararg columns: String) : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false

        fun replaceRows(rows: List<List<String>>) {
            rowCount = 0
            rows.forEach { addRow(it.toTypedArray()) }
        }
    }

    private fun tableOf(model: DefaultTableModel, empty: String): JComponent {
        val table = JBTable(model).apply {
            emptyText.text = empty
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            visibleRowCount = VISIBLE_ROWS
        }
        return ScrollPaneFactory.createScrollPane(table)
    }

    private fun seconds(value: Long) = if (value > 0) Durations.format(value) else "—"

    private fun dayLabel(date: LocalDate, today: LocalDate) = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    private companion object {
        const val VISIBLE_ROWS = 5
        val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
}
