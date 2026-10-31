package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsSyncCodec

/** Reader and audiobook display preferences share a wire document, retaining their own stores. */
internal class CompositeReaderSettingsSyncStore(
    private val reader: ReaderSettingsSyncStore,
    private val sasayaki: ReaderSettingsSyncStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ReaderSettingsSyncStore {
    override suspend fun snapshot(): ReaderSettingsSyncSnapshot {
        val reading = reader.snapshot()
        val audio = sasayaki.snapshot()
        require(reading.deviceId == audio.deviceId) { "Reader preference device identities must agree." }
        return reading.copy(records = reading.records + audio.records)
    }

    override suspend fun merge(remote: Map<String, ReaderSettingRecord>): Boolean = merge(remote) { true }

    override suspend fun merge(
        remote: Map<String, ReaderSettingRecord>,
        stillCurrent: suspend () -> Boolean,
    ): Boolean {
        // Validate both domains before either DataStore is touched. Each store then compares
        // against its latest values inside its own transaction, preserving concurrent edits.
        val now = nowMillis()
        ReaderSettingsSyncCodec.validateIncomingRecords(remote, now)
        SasayakiSettingsSyncCodec.validateIncomingRecords(remote.filterKeys { it.startsWith("sasayaki.") }, now)
        val readerChanged = reader.merge(remote.filterKeys { !it.startsWith("sasayaki.") }, stillCurrent)
        val audioChanged = sasayaki.merge(remote.filterKeys { it.startsWith("sasayaki.") }, stillCurrent)
        return readerChanged || audioChanged
    }
}
