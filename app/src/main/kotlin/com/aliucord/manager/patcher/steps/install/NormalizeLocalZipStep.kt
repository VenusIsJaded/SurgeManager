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

class NormalizeLocalZipStep : Step() {
    companion object {
        private const val RESOURCE_ALIGNMENT = 4
        private const val LOCAL_FILE_HEADER_SIZE = 30
        private const val ZIPALIGN_MAGIC = 0xD935
        private const val EXTRA_FIELD_HEADER_SIZE = 4
    }

    override val group = StepGroup.Install
    override val localizedName = R.string.patch_step_alignment

    override suspend fun execute(container: StepRunner) {
        val apks = container.getStep<CopyDependenciesStep>().patchedApks

        for (apk in apks) {
            container.log("Normalizing zip structure of Local APK: ${apk.name}")
            val tmp = File(apk.parent, "${apk.name}.norm.tmp")

            try {
                ZipFile(apk).use { zipFile ->
                    CountingOutputStream(tmp.outputStream().buffered()).use { countingOut ->
                        ZipOutputStream(countingOut).use { zipOut ->
                            for (entry in zipFile.entries().toList()) {
                                val isResourcesArsc = entry.name == "resources.arsc"
                                val isDexOrSo = entry.name.endsWith(".dex") || entry.name.endsWith(".so")
                                val forceUncompressed = isResourcesArsc || isDexOrSo
                                
                                val alignment = if (isResourcesArsc) RESOURCE_ALIGNMENT else null
                                val method = if (forceUncompressed) ZipEntry.STORED else entry.method

                                // We MUST always buffer the bytes if we are doing ANY metadata stripping
                                // because ZipOutputStream requires perfectly valid size/crc metadata for
                                // STORED entries, and for DEFLATED entries without a Data Descriptor.
                                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                                
                                val crc32 = CRC32().also { it.update(bytes) }.value
                                val sizeBytes = bytes.size.toLong()

                                // Creating a ZipEntry strictly from a String prevents it from copying
                                // the original ZipEntry's corrupt flags (like the 0x08 Data Descriptor bit).
                                // This guarantees apksig sees a perfectly clean Local File Header.
                                val newEntry = ZipEntry(entry.name).apply {
                                    this.method = method
                                    this.size = sizeBytes
                                    this.crc = crc32
                                    if (method == ZipEntry.STORED) {
                                        this.compressedSize = sizeBytes
                                    } else {
                                        // For DEFLATED entries, if we explicitly set the size or crc, 
                                        // ZipOutputStream assumes we want to write them into the Local 
                                        // File Header *without* a Data Descriptor. However, if the 
                                        // compressedSize is unknown beforehand, it forcefully toggles 
                                        // the 0x08 flag anyway!
                                        // By resetting the size and CRC to -1, we explicitly tell 
                                        // ZipOutputStream to stream the compression and generate the 
                                        // Data Descriptor properly at the end of the entry, ensuring
                                        // apksig's parser stays perfectly synced.
                                        this.size = -1L
                                        this.crc = -1L
                                    }
                                    
                                    if (entry.comment != null) comment = entry.comment
                                    
                                    // Strip out manual alignment padding because apksig's 
                                    // setAlignFileSize(true) will perfectly align it for us.
                                }

                                zipOut.putNextEntry(newEntry)
                                zipOut.write(bytes)
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

    private fun createZipAlignExtra(localHeaderOffset: Long, entryName: String, alignment: Int): ByteArray {
        val nameLength = entryName.toByteArray(Charsets.UTF_8).size
        val unpaddedDataOffset = localHeaderOffset + LOCAL_FILE_HEADER_SIZE + nameLength
        var totalExtraLength = ((alignment - (unpaddedDataOffset % alignment)) % alignment).toInt()

        if (totalExtraLength == 0) return ByteArray(0)

        if (totalExtraLength < EXTRA_FIELD_HEADER_SIZE) totalExtraLength += alignment

        val dataLength = totalExtraLength - EXTRA_FIELD_HEADER_SIZE
        val bos = ByteArrayOutputStream(totalExtraLength)

        bos.write(ZIPALIGN_MAGIC and 0xFF)
        bos.write((ZIPALIGN_MAGIC ushr 8) and 0xFF)
        bos.write(dataLength and 0xFF)
        bos.write((dataLength ushr 8) and 0xFF)
        repeat(dataLength) { bos.write(0) }
        
        return bos.toByteArray()
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) { out.write(b); bytesWritten++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytesWritten += len.toLong() }
    }
}
