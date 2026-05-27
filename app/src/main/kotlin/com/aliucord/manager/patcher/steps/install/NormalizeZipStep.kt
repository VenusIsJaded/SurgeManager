package com.aliucord.manager.patcher.steps.install

import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import dev.surgecord.manager.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites each patched APK from scratch using ZipFile (CD-based) + ZipOutputStream.
 *
 * The diamondminer88 ZipWriter leaves a Data Descriptor flag set in Local File Headers
 * (LFH) but NOT in the Central Directory (CD). apksig enforces strict LFH/CD agreement
 * and throws ZipFormatException. ZipInputStream also fails because it reads sizes from
 * the (corrupt) LFH and gets confused by the trailing data descriptors.
 *
 * ZipFile reads entries via the Central Directory, which is always correct, so we use
 * it to enumerate and extract — then write clean entries via ZipOutputStream, which
 * embeds sizes/CRC directly in the LFH with no Data Descriptor flag.
 *
 * Must run AFTER AlignmentStep and BEFORE SigningStep.
 */
class NormalizeZipStep : Step() {
    override val group = StepGroup.Install
    override val localizedName = R.string.patch_step_alignment // no dedicated string; reuse closest

    override suspend fun execute(container: StepRunner) {
        val apks = container.getStep<CopyDependenciesStep>().patchedApks

        for (apk in apks) {
            container.log("Normalizing zip structure of ${apk.name}")
            val tmp = File(apk.parent, "${apk.name}.normalize.tmp")

            try {
                // ZipFile reads the Central Directory — immune to corrupt LFH metadata.
                ZipFile(apk).use { zipFile ->
                    ZipOutputStream(tmp.outputStream().buffered()).use { zipOut ->
                        for (entry in zipFile.entries()) {
                            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }

                            val newEntry = ZipEntry(entry.name).apply {
                                method = entry.method
                                // For STORED entries, ZipOutputStream needs size/CRC up-front
                                // so it can write them into the LFH without a Data Descriptor.
                                if (entry.method == ZipEntry.STORED) {
                                    size = bytes.size.toLong()
                                    compressedSize = bytes.size.toLong()
                                    crc = entry.crc
                                }
                                // Preserve comment if present
                                if (entry.comment != null) comment = entry.comment
                                // Do NOT copy entry.extra — it may carry zip64 extended-info
                                // fields that reference the old offsets and would mislead apksig.
                            }

                            zipOut.putNextEntry(newEntry)
                            zipOut.write(bytes)
                            zipOut.closeEntry()
                        }
                    }
                }

                apk.delete()
                tmp.renameTo(apk)
                container.log("Normalized ${apk.name} successfully")
            } catch (e: Exception) {
                tmp.delete()
                throw Error("Failed to normalize zip for ${apk.name}: ${e.message}", e)
            }
        }
    }
}
