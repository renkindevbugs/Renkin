package dev.renkinProject.renkin.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val text: Int
)

// Source → refresh → edit → build: the same order a first pack actually happens in.
private val OnboardingPages = listOf(
    OnboardingPage(Icons.Filled.Palette, R.string.onboardingSourceTitle, R.string.onboardingSourceText),
    OnboardingPage(Icons.Filled.Refresh, R.string.onboardingRefreshTitle, R.string.onboardingRefreshText),
    OnboardingPage(Icons.Filled.Brush, R.string.onboardingEditTitle, R.string.onboardingEditText),
    OnboardingPage(Icons.Filled.Build, R.string.onboardingBuildTitle, R.string.onboardingBuildText)
)

/**
 * First-run intro: four swipeable cards walking through the app's flow. Icon + text only —
 * no screenshots, so the cards don't go stale as the UI evolves. Skip jumps to the last
 * card (the user still sees how a pack gets applied); Start — and the back gesture — end
 * it for good via [onFinish], which persists the seen flag. Settings → "Show intro"
 * clears the flag to bring it back.
 */
@Composable
fun OnboardingOverlay(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { OnboardingPages.size }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp)
            ) {
                HorizontalPager(pagerState, Modifier.weight(1f)) { index ->
                    val page = OnboardingPages[index]
                    // Scrolls when the card doesn't fit — a large system font or split screen
                    // would otherwise cut the description off. Centred while it does fit.
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CardShape) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .size(44.dp)
                            )
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            text = stringResource(page.title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(page.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(OnboardingPages.size) { index ->
                        val active = pagerState.currentPage == index
                        // The active dot stretches into a pill, matching the pager position.
                        val width by animateDpAsState(if (active) 18.dp else 7.dp, label = "onboardingDot")
                        Box(
                            Modifier
                                .size(width, 7.dp)
                                .clip(SwatchShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val onLastPage = pagerState.currentPage == OnboardingPages.lastIndex
                    if (!onLastPage) {
                        TextButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(OnboardingPages.lastIndex) }
                        }) {
                            Text(stringResource(R.string.onboardingSkip))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (onLastPage) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) {
                        Text(stringResource(if (onLastPage) R.string.onboardingStart else R.string.onboardingNext))
                    }
                }
            }
        }
    }
}
