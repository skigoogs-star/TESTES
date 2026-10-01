package com.deckrec.audio.write

import com.deckrec.data.Marker
import java.io.File

/** A destination the recorder can stream interleaved stereo float audio into. */
interface AudioSink {
    val file: File
    val framesWritten: Long
    val bytesOnDisk: Long

    /** Writes [frames] interleaved stereo frames from [buffer]. */
    fun write(buffer: FloatArray, frames: Int)

    /**
     * Closes the file, patching headers and embedding [markers] where the format allows it.
     *
     * @return true if the file on disk is complete and playable. A container that never received
     * a usable stream (an AAC encoder that produced nothing, say) deletes its own file and returns
     * false, so the caller does not advertise a recording that no player can open.
     */
    fun finish(markers: List<Marker>): Boolean

    /** Closes and deletes the file. Only ever valid for a file containing no committed audio. */
    fun abort()
}
