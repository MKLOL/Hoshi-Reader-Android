package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class V3SentencesPlannerTest {
    @Test
    fun matchingInstalledSentenceContentDoesNotDownloadOrParseTheBookAgain() {
        assertTrue(sentenceActions(localEtag = "sha256:same", remoteEtag = "sha256:same").isEmpty())
    }

    @Test
    fun changedOrMissingSentenceContentIsDownloaded() {
        assertEquals(1, sentenceActions(localEtag = "sha256:old", remoteEtag = "sha256:new").size)
        assertEquals(1, sentenceActions(localEtag = null, remoteEtag = "sha256:new").size)
        assertEquals(1, sentenceActions(localEtag = "sha256:old", remoteEtag = null).size)
    }

    private fun sentenceActions(localEtag: String?, remoteEtag: String?): List<V3Action.ImportSentences> {
        val local = V3LocalBook(
            bookId = "book", syncId = "book", title = "Book", root = File("/book"),
            contentType = ContentType.Epub, shelfName = null, shelfUpdatedAt = null,
            bookmark = null, chatEntries = emptyList(), pendingDeletion = null,
            sentencesEtag = localEtag,
        )
        val remote = V3RemoteBook(
            syncId = "book", sentencesKey = "books/book/sentences", sentencesSize = 100,
            sentencesEtag = remoteEtag,
        )
        return V3Planner().compute(
            V3LocalSnapshot(listOf(local), null, Instant.EPOCH),
            V3RemoteSnapshot(mapOf("book" to remote)),
        ).actions.filterIsInstance<V3Action.ImportSentences>()
    }
}
