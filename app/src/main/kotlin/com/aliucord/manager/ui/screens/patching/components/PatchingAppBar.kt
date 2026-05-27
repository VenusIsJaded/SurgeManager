package com.aliucord.manager.ui.screens.patching.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surgecord.manager.R

@Composable
fun PatchingAppBar(
    onBack: () -> Unit,
    discordVersion: String? = null,
    isLocalApk: Boolean = false,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.installer),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(
                    visible = discordVersion != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    )
                    {
                        Text(
                            text = "Discord $discordVersion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isLocalApk) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.height(16.dp)
                            ) {
                                Text(
                                    text = "LOCAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    modifier = Modifier.padding(horizontal = 3.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.navigation_back),
                )
            }
        }
    )
}
