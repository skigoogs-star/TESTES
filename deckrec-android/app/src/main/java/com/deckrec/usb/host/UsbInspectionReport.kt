package com.deckrec.usb.host

import com.deckrec.usb.UsbDeviceClass
import com.deckrec.usb.UsbInterfaceSummary

/**
 * What the app could work out about one USB device, in a form a user can send on.
 *
 * Kept free of `android.*` so the rendering is a pure function: the report is the only channel
 * through which hardware that is not in [PioneerQuirks] can ever be added to it, so it needs to be
 * complete, unambiguous and impossible to get subtly wrong.
 */
data class UsbInspectionReport(
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val permissionGranted: Boolean,
    val rawDescriptors: ByteArray?,
    val parsed: UsbDescriptors.UsbDeviceDescriptors?,
    val profile: UsbStreamProfile?,
    val knownModel: String?,
    /** Result of the DJM-850 family's input-switch read, when it was attempted. */
    val vendorProbe: VendorProbe?,
    val failure: String?,
) {
    data class VendorProbe(val bytes: ByteArray?, val returnCode: Int) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    // Arrays make the generated data-class equality meaningless; identity is what callers want.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    fun render(): String = buildString {
        appendLine("DeckRec USB device report")
        appendLine("=========================")
        appendLine()
        appendLine("Device : $manufacturerName $productName".trim())
        appendLine(String.format("USB id : %04X:%04X", vendorId, productId))
        appendLine("Known  : ${knownModel ?: "not in the built-in table"}")
        appendLine("Access : ${if (permissionGranted) "granted" else "DENIED — nothing below could be read"}")
        failure?.let { appendLine("Error  : $it") }
        appendLine()

        val descriptors = parsed
        if (descriptors == null) {
            appendLine("No descriptors could be read.")
        } else {
            appendLine("Interfaces")
            appendLine("----------")
            if (descriptors.interfaces.isEmpty()) appendLine("  (none)")
            descriptors.interfaces.forEach { iface ->
                appendLine(
                    "  iface %d alt %d  class 0x%02X sub 0x%02X  %d endpoint(s)%s".format(
                        iface.number,
                        iface.alternateSetting,
                        iface.interfaceClass,
                        iface.interfaceSubclass,
                        iface.endpoints.size,
                        if (iface.isVendorSpecific) "  [vendor specific]" else "",
                    )
                )
                iface.endpoints.forEach { endpoint ->
                    appendLine(
                        "      ep 0x%02X  %-12s %-13s max %4d  interval %d".format(
                            endpoint.address,
                            if (endpoint.isInput) "IN (capture)" else "OUT (play)",
                            transferName(endpoint.transferType),
                            endpoint.maxPacketSize,
                            endpoint.interval,
                        )
                    )
                }
            }
            appendLine()

            val capability = UsbDeviceClass.classify(
                descriptors.interfaces.map {
                    UsbInterfaceSummary(
                        interfaceClass = it.interfaceClass,
                        interfaceSubclass = it.interfaceSubclass,
                        isochronousInputs = it.endpoints.count { e -> e.isInput && e.isIsochronous },
                    )
                }
            )
            appendLine("Verdict")
            appendLine("-------")
            appendLine("  Android can route it   : ${yesNo(capability.isRoutableByAndroid)}")
            appendLine("  Direct capture possible: ${yesNo(capability.needsDirectCapture)}")
            appendLine()
        }

        appendLine("Capture stream")
        appendLine("--------------")
        if (profile == null) {
            appendLine("  none found — no isochronous input endpoint on this device")
        } else {
            appendLine(String.format("  endpoint      : 0x%02X", profile.endpointAddress))
            appendLine("  interface/alt : ${profile.interfaceNumber}/${profile.alternateSetting}")
            appendLine("  channels      : ${profile.channels}${if (profile.channelsAreProvisional) " (GUESS)" else ""}")
            appendLine("  encoding      : ${profile.encoding}")
            appendLine("  rate          : ${profile.rate} Hz")
            appendLine("  max packet    : ${profile.maxPacketSize} bytes, interval ${profile.interval}")
            appendLine("  expected      : ${profile.nominalPacketBytes} bytes per packet, ${profile.packetsPerSecond}/s")
        }
        appendLine()

        vendorProbe?.let { probe ->
            appendLine("Vendor register probe (DJM-850 input-switch read)")
            appendLine("------------------------------------------------")
            if (probe.bytes != null && probe.returnCode > 0) {
                appendLine("  answered ${probe.returnCode} bytes: ${probe.bytes.toHex()}")
            } else {
                appendLine("  no answer (returned ${probe.returnCode}) — this device does not use that register space")
            }
            appendLine()
        }

        rawDescriptors?.let { raw ->
            appendLine("Raw descriptors (${raw.size} bytes)")
            appendLine("-------------------------------")
            raw.toList().chunked(16).forEachIndexed { line, chunk ->
                appendLine(
                    "  %04X  %s".format(
                        line * 16,
                        chunk.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    )
                )
            }
        }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun transferName(type: Int) = when (type) {
        0 -> "control"
        1 -> "isochronous"
        2 -> "bulk"
        3 -> "interrupt"
        else -> "type $type"
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
