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
                                
                                val newEntry = ZipEntry(entry.name).apply {
                                    this.method = method
                                    if (entry.comment != null) comment = entry.comment
                                    
                                    if (alignment != null) {
                                        extra = createZipAlignExtra(
                                            localHeaderOffset = countingOut.bytesWritten,
                                            entryName = entry.name,
                                            alignment = alignment,
                                        )
                                    }
                                }

                                // If the original entry is stored, we can stream directly into the new entry
                                // without recalculating CRCs or keeping byte arrays in memory, as long as we 
                                // set the exact metadata beforehand.
                                if (method == ZipEntry.STORED) {
                                    // We ALWAYS buffer STORED entries that were previously DEFLATED,
                                    // or entries with Data Descriptors. ZipOutputStream refuses to write 
                                    // STORED files without knowing their exact uncompressed size and CRC beforehand.
                                    if (entry.size < 0 || entry.crc < 0 || entry.method != ZipEntry.STORED) {
                                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                                        newEntry.size = bytes.size.toLong()
                                        newEntry.compressedSize = bytes.size.toLong()
                                        newEntry.crc = CRC32().also { it.update(bytes) }.value
                                        
                                        zipOut.putNextEntry(newEntry)
                                        zipOut.write(bytes)
                                        zipOut.closeEntry()
                                    } else {
                                        newEntry.size = entry.size
                                        newEntry.compressedSize = entry.compressedSize
                                        newEntry.crc = entry.crc
                                        
                                        zipOut.putNextEntry(newEntry)
                                        zipFile.getInputStream(entry).use { input -> input.copyTo(zipOut) }
                                        zipOut.closeEntry()
                                    }
                                } else {
                                    // apksig has a massive bug where if it encounters a DEFLATED file in an APK
                                    // without the 0x08 flag in the local file header, it throws an exception.
                                    // However, Java's ZipOutputStream refuses to write the 0x08 flag if we don't 
                                    // explicitly set the size to -1! It assumes we are providing it immediately.
                                    
                                    newEntry.size = -1
                                    newEntry.compressedSize = -1
                                    newEntry.crc = -1
                                    
                                    zipOut.putNextEntry(newEntry)
                                    zipFile.getInputStream(entry).use { input -> input.copyTo(zipOut) }
                                    zipOut.closeEntry()
                                }
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
