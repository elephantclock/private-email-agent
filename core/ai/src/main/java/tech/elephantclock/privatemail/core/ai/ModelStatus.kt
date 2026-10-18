package tech.elephantclock.privatemail.core.ai

/**
 * The on-device capabilities the app uses. ML Kit reports each separately (Prompt, Summarization,
 * Rewriting, Proofreading, Translation models), so availability is tracked per feature.
 */
enum class AiFeature {
    /** Prompt API: classification and reply drafts. */
    PROMPT,
    SUMMARIZE,
    REWRITE,
    PROOFREAD,
    LANGUAGE_ID,
    TRANSLATE,
}

/** Why a feature can't run on this device right now. */
enum class UnavailableReason {
    /** No Gemini Nano / AICore on this device (or an emulator). Permanent. */
    NOT_SUPPORTED,

    /** AICore or the model is present but not ready (e.g. update pending, low storage). */
    NOT_READY,

    /** The user turned AI off in settings. */
    DISABLED_BY_USER,

    /** Status not known yet (before the first check) or the check failed. */
    UNKNOWN,
}

/**
 * State of one feature's model. Mirrors ML Kit GenAI's `FeatureStatus` (AVAILABLE, DOWNLOADABLE,
 * DOWNLOADING, UNAVAILABLE) without depending on ML Kit; the T31 implementation maps it.
 */
sealed interface ModelStatus {
    data object Available : ModelStatus

    /** Supported, but the model must be downloaded first. */
    data class Downloadable(
        val sizeBytes: Long? = null,
    ) : ModelStatus

    data class Downloading(
        val downloadedBytes: Long = 0,
        val totalBytes: Long? = null,
    ) : ModelStatus {
        /** 0..1, or null when the size is unknown. */
        val progress: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    }

    data class Unavailable(
        val reason: UnavailableReason,
    ) : ModelStatus

    /** Only [Available] may be called; everything else means the rules decide alone. */
    val canRun: Boolean get() = this == Available

    /** Worth offering a download (Wi-Fi) in settings. */
    val canDownload: Boolean get() = this is Downloadable
}
