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

    /** Closes the file, patching headers and embedding [markers] where the format allows it. */
    fun finish(markers: List<Marker>)

    /** Closes and deletes the file. */
    fun abort()
}
