package com.damonearl.fantasymultiview

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.damonearl.fantasymultiview.ui.AppTheme
import com.damonearl.fantasymultiview.ui.AppViewModel
import com.damonearl.fantasymultiview.ui.SetupScreen
import com.damonearl.fantasymultiview.ui.WatchScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AppTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val ready = state.matchup != null

                if (ready && !showSettings) {
                    WatchScreen(state, viewModel.players, onOpenSettings = { showSettings = true })
                } else {
                    BackHandler(enabled = ready) { showSettings = false }
                    SetupScreen(
                        state = state,
                        configUrl = viewModel.configServer.url,
                        onSelectTeam = viewModel::selectTeam,
                        onDone = if (ready) ({ showSettings = false }) else null,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.players.resumeAll()
    }

    override fun onStop() {
        viewModel.players.pauseAll()
        super.onStop()
    }
}
