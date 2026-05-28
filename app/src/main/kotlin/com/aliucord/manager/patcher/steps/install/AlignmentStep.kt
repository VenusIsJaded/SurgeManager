package com.aliucord.manager.patcher.steps.install

import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import dev.surgecord.manager.R
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Normalizes the zip structure of every patched APK so that apksig can sign them,
 * stores and aligns resources.arsc as required for targetSdk 30+,
 * AND aligns native libraries to 16KB for Android 15+ (API 35+) support.
 *
 * LSPatch writes APKs using a ZipOutputStream variant that sets the Data Descriptor
 * flag (0x08) in Local File Headers but does NOT write actual Data Descriptor records,
 * and leaves LFH size/CRC fields as zero. apksig's zip parser enforces strict
 * LFH/CD agreement and throws ZipFormatException on these entries.
 *
 * Android 11+ requires resources.arsc in targetSdk 30+ APKs to be stored
 * uncompressed and aligned on a 4-byte boundary.
 *
 * Android 15+ / 16KB-page devices also require every uncompressed native library's
 * *data offset* in the APK to be aligned to a 16KB boundary. Alignment depends on
 * the current local-file-header offset plus the entry name and extra-field length;
 * it cannot be calculated from the native library's file size.
 *
 * Runs before SigningStep. In the official/auto-download pipeline LSPatch runs after
 * signing and produces the final signed APK; in local-APK recovery scenarios this
 * step still prepares the input APKs so LSPatch starts from correctly aligned ZIPs.
 */
class AlignmentStep : Step() {
    companion object {
        /** 4-byte alignment required for resources.arsc on targetSdk 30+ APKs. */
        private const val RESOURCE_ALIGNMENT = 4

        /** 16KB alignment required for native libraries on Android 15+ (API 35+) */
        private const val LIBRARY_ALIGNMENT = 16 * 1024

        /** Local file header size without variable name/extra fields. */
        private const val LOCAL_FILE_HEADER_SIZE = 30

        /** Android zipalign extra field magic bytes (0xD935), little-endian in the ZIP. */
        private const val ZIPALIGN_MAGIC = 0xD935

        /** Zip extra fields use a 2-byte header ID and a 2-byte data-size prefix. */
        private const val EXTRA_FIELD_HEADER_SIZE = 4
    }

    override val group = StepGroup.Install
    override val localizedName = R.string.patch_step_alignment

    override suspend fun execute(container: StepRunner) {
        val apks = container.getStep<CopyDependenciesStep>().patchedApks

        for (apk in apks) {
            container.log("Normalizing zip structure of APK: ${apk.name}")
            val tmp = File(apk.parent, "${apk.name}.norm.tmp")

            try {
                // ZipFile reads via the Central Directory -- immune to corrupt LFH metadata.
                ZipFile(apk).use { zipFile ->
                    CountingOutputStream(tmp.outputStream().buffered()).use { countingOut ->
                        ZipOutputStream(countingOut).use { zipOut ->
                            for (entry in zipFile.entries().toList()) {
                                val isNativeLib = isNativeLibrary(entry.name)
                                val isResourcesArsc = isResourcesArsc(entry.name)
                                val alignment = when {
                                    isNativeLib -> LIBRARY_ALIGNMENT
                                    isResourcesArsc -> RESOURCE_ALIGNMENT
                                    else -> null
                                }
                                val method = if (alignment != null) ZipEntry.STORED else entry.method

                                val fallbackStoredBytes = if (method == ZipEntry.STORED &&
                                    (entry.size < 0 || entry.crc < 0)
                                ) {
                                    // Extremely defensive fallback: ZipFile entries should have size
                                    // and CRC from the central directory, but compute them if not.
                                    zipFile.getInputStream(entry).use { it.readBytes() }
                                } else {
                                    null
                                }
                                val fallbackStoredCrc = fallbackStoredBytes?.let { bytes ->
                                    CRC32().also { it.update(bytes) }.value
                                }

                                val newEntry = ZipEntry(entry.name).apply {
                                    this.method = method

                                    if (method == ZipEntry.STORED) {
                                        // ZipOutputStream needs size/CRC upfront for STORED entries.
                                        // Use the central directory metadata instead of buffering each
                                        // file; the CRC is for the uncompressed bytes even if the
                                        // source entry was deflated and is being converted to STORED.
                                        val storedSize = fallbackStoredBytes?.size?.toLong() ?: entry.size
                                        size = storedSize
                                        compressedSize = storedSize
                                        crc = fallbackStoredCrc ?: entry.crc
                                    }

                                    if (entry.comment != null) comment = entry.comment

                                    // Do NOT copy the original extra field: old alignment padding and
                                    // LSPatch metadata can become stale once the archive is rewritten.
                                    if (alignment != null) {
                                        extra = createZipAlignExtra(
                                            localHeaderOffset = countingOut.bytesWritten,
                                            entryName = entry.name,
                                            alignment = alignment,
                                        ).also {
                                            val type = if (isNativeLib) "native library" else "resources.arsc"
                                            if (it.isNotEmpty()) {
                                                container.log(
                                                    "Aligned $type ${entry.name} with ${it.size} bytes of LFH extra padding"
                                                )
                                            } else {
                                                container.log("$type ${entry.name} already aligned")
                                            }
                                        }
                                    }
                                }

                                zipOut.putNextEntry(newEntry)
                                if (fallbackStoredBytes != null) {
                                    zipOut.write(fallbackStoredBytes)
                                } else {
                                    zipFile.getInputStream(entry).use { input -> input.copyTo(zipOut) }
                                }
                                zipOut.closeEntry()
                            }
                        }
                    }
                }

                if (!apk.delete()) throw Error("Failed to delete old APK ${apk.name}")
                if (!tmp.renameTo(apk)) throw Error("Failed to replace APK ${apk.name}")
                container.log("Normalized ${apk.name}")
            } catch (e: Exception) {
                tmp.delete()
                throw Error("Failed to normalize zip for ${apk.name}: ${e.message}", e)
            }
        }
    }

    /**
     * Checks if the entry is a native library that needs alignment.
     * Native libraries are stored in lib/<abi>/ directories with .so extension.
     */
    private fun isNativeLibrary(name: String): Boolean {
        val path = name.replace('\\', '/')
        return path.startsWith("lib/") && path.endsWith(".so")
    }

    /** Checks if the entry is the root resources table, which must be stored/aligned. */
    private fun isResourcesArsc(name: String): Boolean {
        val path = name.replace('\\', '/')
        return path == "resources.arsc"
    }

    /**
     * Creates an Android zipalign extra field that makes this entry's *data offset*
     * a multiple of [alignment]. [localHeaderOffset] must be the current byte offset
     * immediately before ZipOutputStream writes the entry's local file header.
     */
    private fun createZipAlignExtra(localHeaderOffset: Long, entryName: String, alignment: Int): ByteArray {
        val nameLength = entryName.toByteArray(Charsets.UTF_8).size
        val unpaddedDataOffset = localHeaderOffset + LOCAL_FILE_HEADER_SIZE + nameLength
        var totalExtraLength = ((alignment - (unpaddedDataOffset % alignment)) % alignment).toInt()

        if (totalExtraLength == 0) {
            return ByteArray(0)
        }

        // A ZIP extra field has 4 bytes of header before its payload. If the exact
        // padding needed is smaller than that, wrap to the next alignment boundary.
        if (totalExtraLength < EXTRA_FIELD_HEADER_SIZE) {
            totalExtraLength += alignment
        }

        val dataLength = totalExtraLength - EXTRA_FIELD_HEADER_SIZE
        val bos = ByteArrayOutputStream(totalExtraLength)

        // Header ID: 0xD935, little-endian.
        bos.write(ZIPALIGN_MAGIC and 0xFF)
        bos.write((ZIPALIGN_MAGIC ushr 8) and 0xFF)

        // Payload length, little-endian.
        bos.write(dataLength and 0xFF)
        bos.write((dataLength ushr 8) and 0xFF)

        repeat(dataLength) { bos.write(0) }
        return bos.toByteArray()
    }

    /** Counts bytes that ZipOutputStream has emitted so alignment can be calculated. */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }
}
