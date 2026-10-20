package com.zero.v2.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.settings.SystemStatusCard
import com.zero.v2.app.ui.theme.ZeroNavigationRow
import com.zero.v2.app.ui.theme.ZeroNavigationGroup
import com.zero.v2.app.ui.theme.ZeroNavigationDivider
import com.zero.v2.app.ui.theme.ZeroPageHeader
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootHubScreen(title: String, description: String, destinations: List<AppDestination>,
    onNavigate: (String) -> Unit, showStatus: Boolean = false) {
    Scaffold(topBar = { ZeroPageHeader(title) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(description, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (showStatus) item {
                val viewModel: SystemStatusViewModel = hiltViewModel()
                val snapshot by viewModel.snapshot.collectAsState()
                SystemStatusCard(snapshot)
            }
            item(key = "destinations") {
                ZeroNavigationGroup {
                    destinations.forEachIndexed { index, destination ->
                        if (index > 0) ZeroNavigationDivider()
                        ZeroNavigationRow(destination.title, destination.description,
                            icon = destinationIcon(destination.route), containerColor = Color.Transparent) {
                            onNavigate(destination.route)
                        }
                    }
                }
            }
        }
    }
}
