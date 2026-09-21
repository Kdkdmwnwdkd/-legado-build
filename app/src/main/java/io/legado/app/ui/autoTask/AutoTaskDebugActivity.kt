package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskDebugBinding
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class AutoTaskDebugActivity : VMBaseActivity<ActivityAutoTaskDebugBinding, AutoTaskDebugViewModel>() {

    override val binding by viewBinding(ActivityAutoTaskDebugBinding::inflate)
    override val viewModel by viewModels<AutoTaskDebugViewModel>()

    private var debugState by mutableStateOf<AutoTaskDebugState>(AutoTaskDebugState.Idle)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            val running = debugState is AutoTaskDebugState.Running
            val title = when (val state = debugState) {
                is AutoTaskDebugState.Running -> state.task.name
                is AutoTaskDebugState.Finished -> state.task.name
                is AutoTaskDebugState.Cancelled -> state.task.name
                else -> getString(R.string.auto_task_debug_title)
            }.ifBlank { getString(R.string.auto_task_debug_title) }
            AppManagementScaffold(
                title = title,
                selectedCount = 0,
                totalCount = 0,
                topActions = listOf(
                    AppManagementAction(
                        text = getString(R.string.auto_task_debug_cancel),
                        iconRes = R.drawable.ic_stop_black_24dp,
                        danger = true,
                        onClick = viewModel::cancel,
                        menuActions = null
                    ).takeIf { running } ?: AppManagementAction(
                        text = getString(R.string.more_menu),
                        iconRes = R.drawable.ic_more_vert,
                        menuActions = ::debugMenuActions
                    )
                ),
                onBack = ::finish
            ) {
                AutoTaskDebugScreen(
                    state = debugState,
                    onCancel = viewModel::cancel,
                    onClearLogs = viewModel::clearLiveLogs
                )
            }
        }
        observeState()
        viewModel.loadAndStart(intent.getStringExtra(EXTRA_TASK_ID))
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { debugState = it }
            }
        }
    }

    private fun debugMenuActions(): List<AppManagementMenuAction> = listOf(
        AppManagementMenuAction(
            text = getString(R.string.auto_task_debug_clear),
            onClick = viewModel::clearLiveLogs
        )
    )

    companion object {
        private const val EXTRA_TASK_ID = "taskId"

        fun startIntent(context: Context, taskId: String): Intent =
            Intent(context, AutoTaskDebugActivity::class.java).putExtra(EXTRA_TASK_ID, taskId)
    }
}
