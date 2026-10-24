package com.speechnotes.app.playback

/**
 * The meeting point between the UI-scoped players and the playback service.
 * The ViewModel registers both players here at creation; the foreground
 * service reads them to publish the lock screen and to route remote
 * commands. (Port of the iOS app's NowPlayingCenter wiring: SpeechPlayer and
 * AudioBookPlayer are separate owners of "what is playing", and the session
 * decides who a button press belongs to.)
 */
object PlaybackRegistry {

    @Volatile
    var speechPlayer: com.speechnotes.app.speech.SpeechPlayer? = null

    @Volatile
    var audioBooks: com.speechnotes.app.speech.AudioBookPlayer? = null
}
