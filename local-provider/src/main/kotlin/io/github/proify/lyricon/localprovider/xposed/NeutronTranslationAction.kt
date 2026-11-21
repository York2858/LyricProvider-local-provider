package io.github.proify.lyricon.localprovider.xposed

import android.media.session.PlaybackState

internal object NeutronTranslationAction {
    const val ACTION = "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"

    fun update(state: PlaybackState, enabled: Boolean, icon: Int): PlaybackState {
        if (state.customActions.any { it.action == ACTION } == enabled) return state

        return PlaybackState.Builder()
            .setState(state.state, state.position, state.playbackSpeed, state.lastPositionUpdateTime)
            .setBufferedPosition(state.bufferedPosition)
            .setActions(state.actions)
            .setActiveQueueItemId(state.activeQueueItemId)
            .setErrorMessage(state.errorMessage)
            .setExtras(state.extras)
            .apply {
                if (enabled) addCustomAction(ACTION, "翻译", icon)
                state.customActions.filterNot { it.action == ACTION }.forEach { addCustomAction(it) }
            }.build()
    }
}
