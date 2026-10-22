// Public Lyricon subscriber wire contract. Apache-2.0, Proify / Tomakino.
package io.github.proify.lyricon.subscriber;
interface IActivePlayerListener {
    void onActiveProviderChanged(in byte[] providerInfo);
    void onSongChanged(in byte[] song);
    void onPlaybackStateChanged(boolean isPlaying);
    void onSeekTo(long position);
    void onReceiveText(String text);
    void onDisplayTranslationChanged(boolean isDisplayTranslation);
    void onDisplayRomaChanged(boolean isDisplayRoma);
}
