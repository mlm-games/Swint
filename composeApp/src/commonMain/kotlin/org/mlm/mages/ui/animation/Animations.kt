package org.mlm.mages.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import org.mlm.mages.nav.NAV_ANIM_DURATION
import org.mlm.mages.ui.theme.Durations

object AnimationSpecs {
    val fadeIn = fadeIn(tween(Durations.medium))
    val fadeOut = fadeOut(tween(Durations.medium))

    fun contentTransform(): ContentTransform =
        fadeIn(tween(Durations.medium)) togetherWith
                fadeOut(tween(Durations.medium))
}

fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    this.alpha(alpha)
}

@Composable
fun rememberPulseAlpha(index: Int): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(index * 100)
        ),
        label = "pulseAlpha$index"
    )
    return alpha
}

val forwardTransition: (AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform) =
    {
        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(NAV_ANIM_DURATION)) +
                fadeIn(tween(NAV_ANIM_DURATION)) togetherWith
                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(NAV_ANIM_DURATION)) +
                fadeOut(tween(NAV_ANIM_DURATION))
    }
val popTransition: (AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform) =
    {
        slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(NAV_ANIM_DURATION)) +
                fadeIn(tween(NAV_ANIM_DURATION)) togetherWith
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_DURATION)) +
                fadeOut(tween(NAV_ANIM_DURATION))
    }