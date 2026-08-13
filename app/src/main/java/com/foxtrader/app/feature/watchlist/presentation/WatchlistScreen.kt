package com.foxtrader.app.feature.watchlist.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Watchlist
import com.foxtrader.app.domain.repository.WatchlistRepository
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxEmptyState
import com.foxtrader.app.ui.components.FoxIconButton
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSearchField
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistScreenViewModel @Inject constructor(
    private val watchlistRepository: WatchlistRepository,
) : ViewModel() {

    val lists: StateFlow<List<Watchlist>> = watchlistRepository.observeWatchlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { watchlistRepository.ensureSeeded() }
    }

    fun addSymbol(watchlistId: String, symbol: String) {
        val cleaned = symbol.trim().uppercase()
        if (cleaned.isBlank()) return
        viewModelScope.launch { watchlistRepository.addSymbol(watchlistId, cleaned) }
    }

    fun removeSymbol(watchlistId: String, symbol: String) {
        viewModelScope.launch { watchlistRepository.removeSymbol(watchlistId, symbol) }
    }

    fun move(watchlistId: String, from: Int, to: Int) {
        viewModelScope.launch { watchlistRepository.moveSymbol(watchlistId, from, to) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: WatchlistScreenViewModel = hiltViewModel(),
) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val active = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
    var draft by remember { mutableStateOf("") }
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Watchlist", onNavigateBack = onNavigateBack) },
    ) { padding ->
        if (active == null) {
            FoxEmptyState(
                title = "No watchlist",
                subtitle = "A default list is created on first launch.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            Text(active.name, style = FoxTheme.type.h3, color = colors.textPrimary)
            Text("${active.size} symbols", style = FoxTheme.type.caption, color = colors.textMuted)
            FoxSearchField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Add symbol",
                modifier = Modifier.padding(vertical = spacing.sm),
            )
            FoxButton(
                text = "Add symbol",
                onClick = {
                    viewModel.addSymbol(active.id, draft)
                    draft = ""
                },
                icon = Icons.Outlined.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            if (active.symbols.isEmpty()) {
                FoxEmptyState(
                    title = "Empty list",
                    subtitle = "Add a symbol above. Classification is inferred from the ticker.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    itemsIndexed(active.symbols, key = { _, item -> item.symbol }) { index, item ->
                        FoxPanel {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.symbol, style = FoxTheme.type.h3, color = colors.textPrimary)
                                    FoxBadge(item.assetClass.name)
                                }
                                Row {
                                    FoxIconButton(
                                        icon = Icons.Outlined.KeyboardArrowUp,
                                        contentDescription = "Move ${item.symbol} up",
                                        onClick = { if (index > 0) viewModel.move(active.id, index, index - 1) },
                                        enabled = index > 0,
                                    )
                                    FoxIconButton(
                                        icon = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Move ${item.symbol} down",
                                        onClick = {
                                            if (index < active.symbols.lastIndex) {
                                                viewModel.move(active.id, index, index + 1)
                                            }
                                        },
                                        enabled = index < active.symbols.lastIndex,
                                    )
                                    FoxIconButton(
                                        icon = Icons.Outlined.Delete,
                                        contentDescription = "Remove ${item.symbol} from watchlist",
                                        onClick = { viewModel.removeSymbol(active.id, item.symbol) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
