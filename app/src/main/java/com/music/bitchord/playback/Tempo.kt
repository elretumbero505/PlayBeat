package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings

/**
 * PlayBeat: aplica la velocidad del listener con el acople velocidad–tono.
 *
 * Con el ajuste "velocidad y tono juntos" activado, el tempo se convierte en
 * varispeed de tocadiscos: bajar la velocidad baja también el tono en la misma
 * proporción, en vez de conservarlo con el estiramiento de Sonic. Apagado,
 * queda el comportamiento normal de la app: el tono se mantiene.
 *
 * Central aquí porque el tempo se aplica desde muchos puntos — ajustes
 * nuevos, reparto entre los dos decks del crossfade, el reinicio "clickless"
 * tras una transición — y todos tienen que leer el mismo ajuste.
 */
@UnstableApi
fun Player.applyTempo(speed: Float) {
    val linked = AppSettings.speedPitchLinked.value
    playbackParameters = PlaybackParameters(speed, if (linked) speed else 1.0f)
}
