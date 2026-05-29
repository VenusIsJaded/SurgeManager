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

                                val fallbackStoredBytes = if (method == ZipEntry.STORED && (entry.size < 0 || entry.crc < 0 || entry.method != ZipEntry.STORED)) {
                                    zipFile.getInputStream(entry).use { it.readBytes() }
                                } else {
                                    null
                                }
                                
                                val fallbackStoredCrc = fallbackStoredBytes?.let { bytes ->
                                    CRC32().also { it.update(bytes) }.value
                                }

                                // ZipOutputStream automatically recalculates the CRC and Size 
                                // as it streams the bytes in if we don't declare it. By not explicitly
                                // declaring the size on DEFLATED entries, it implicitly writes the 
                                // proper Data Descriptor record at the end of the entry stream, which
                                // makes apksig perfectly happy.
                                val newEntry = ZipEntry(entry.name).apply {
                                    this.method = method

                                    if (method == ZipEntry.STORED) {
                                        val storedSize = fallbackStoredBytes?.size?.toLong() ?: entry.size
                                        size = storedSize
                                        compressedSize = storedSize
                                        crc = fallbackStoredCrc ?: entry.crc
                                    }

                                    if (entry.comment != null) comment = entry.comment
                                    
                                    if (alignment != null) {
                                        extra = createZipAlignExtra(
                                            localHeaderOffset = countingOut.bytesWritten,
                                            entryName = entry.name,
                                            alignment = alignment,
                                        )
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
