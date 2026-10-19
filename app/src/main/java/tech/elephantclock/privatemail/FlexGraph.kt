package tech.elephantclock.privatemail

import androidx.compose.ui.platform.LocalContext
import tech.elephantclock.privatemail.feature.shell.FlexReadingDetail
import tech.elephantclock.privatemail.feature.shell.PlaceholderDetail
import tech.elephantclock.privatemail.feature.shell.ShellDestination
import tech.elephantclock.privatemail.feature.shell.ShellDetailPane

/*
 * T4A wiring, as extensions so MailGraph and ReadingGraph stay untouched while other lanes work.
 * The coordinator swaps `graph.readingDetailPane()` for `graph.flexReadingDetailPane()` in
 * MainActivity.
 */

/**
 * Like [readingDetailPane], but Flex-aware: in tabletop posture the email stays above the hinge
 * and the reply editor opens below it. Same ViewModel keys, so drafts carry over.
 */
fun MailGraph.flexReadingDetailPane(other: ShellDetailPane = { _, _ -> PlaceholderDetail() }): ShellDetailPane =
    { destination, key ->
        when (destination) {
            ShellDestination.Priority, ShellDestination.Folders -> {
                val id = key.toLongOrNull()
                if (id == null) {
                    PlaceholderDetail()
                } else {
                    val cacheDir = LocalContext.current.cacheDir
                    FlexReadingDetail(
                        messageId = id,
                        detailViewModel = { detailViewModel(id, cacheDir) },
                        replyViewModel = { replyViewModel(id) },
                    )
                }
            }

            else -> {
                other(destination, key)
            }
        }
    }
