package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import moe.antimony.hoshi.epub.SasayakiSidecarRepository
import moe.antimony.hoshi.epub.SasayakiPlaybackWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File

interface SasayakiPlaybackRepository {
    suspend fun load(): SasayakiPlaybackData?
    suspend fun save(playback: SasayakiPlaybackData)

    fun enqueueSave(playback: SasayakiPlaybackData, scope: CoroutineScope): Job =
        SasayakiPlaybackWrites.enqueue(this, scope) { save(playback) }
}

class BookSasayakiPlaybackRepository(
    private val bookRoot: File,
    private val sidecarRepository: SasayakiSidecarRepository,
) : SasayakiPlaybackRepository {
    override suspend fun load(): SasayakiPlaybackData? {
        SasayakiPlaybackWrites.await(SasayakiPlaybackWrites.bookKey(bookRoot))
        return sidecarRepository.loadSasayakiPlayback(bookRoot)
    }

    override suspend fun save(playback: SasayakiPlaybackData) {
        sidecarRepository.saveSasayakiPlayback(bookRoot, playback)
    }

    override fun enqueueSave(playback: SasayakiPlaybackData, scope: CoroutineScope): Job =
        sidecarRepository.enqueueSasayakiPlaybackSave(bookRoot, playback, scope)
}
