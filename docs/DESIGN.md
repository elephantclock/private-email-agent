# PEA (Private Email Agent): design plan (v0.3, 21 Oct 2026)

## Goals

1. Classify, summarise and suggest editable replies for any email account.
2. On first login, sort the last 30 days into value folders and discard the rest, after the user reviews it.
3. Triage new mail as it arrives; notify only for urgent and high-priority mail.
4. Fast and battery-efficient; use what Android, Google and Samsung already ship instead of bundling models.
5. Responsive on the Galaxy Z Fold7 (cover, inner portrait, inner landscape, Flex mode).

## Architecture

| Layer | Choice |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 Adaptive (`NavigationSuiteScaffold`, `ListDetailPaneScaffold`) |
| Fold posture | Jetpack WindowManager (`FoldingFeature`) for Flex mode |
| Mail | Angus Mail (IMAP/SMTP). IDLE while the app is open; WorkManager every 15 min otherwise |
| Accounts | Password sign-in via autoconfig (Thunderbird ISPDB, DNS SRV) or manual settings; Google and Microsoft OAuth (XOAUTH2) in phase 7 |
| Storage | Room + FTS; one row per message with its verdict, keyed by Message-ID |
| Secrets | Android Keystore (AES-GCM) for IMAP passwords; OAuth refresh tokens likewise |
| On-device AI | ML Kit GenAI APIs on Gemini Nano via AICore: Prompt (classify, draft), Summarization, Rewriting, Proofreading |
| Language | ML Kit Language ID from Google Play services; translation with Gemini Nano (Prompt API), weekdays checked |

## Triage pipeline

1. **Fetch** headers, BODYSTRUCTURE and the first ~4 KB of text. Attachments only on tap.
2. **Protections** (never discard): sender in Sent history or Contacts, thread you've replied in, VIP list, security codes, payment/invoice signals.
3. **Rules** (instant, account-agnostic):
   - Bulk: `List-Unsubscribe`, `List-Id`, `Precedence: bulk`, ESP headers, no-reply local parts, `Auto-Submitted`.
   - Trust: `Authentication-Results` (SPF/DKIM/DMARC), hidden or zero-width characters, look-alike domains → Junk.
   - Learned rules from the user's corrections (sender or domain → folder).
4. **Gemini Nano** for anything rules can't decide: importance (urgent/high/normal/low), folder (chosen from the user's folder names and plain-language descriptions), one-line summary, reason. Structured output only; the model has no tools.
5. **Act:** file into the folder, discard, or leave in Priority. Notify for urgent and high.

Every result is cached, so nothing is classified twice. AICore runs Gemini Nano only for the app on screen, so AI work is drained in the foreground; background sync uses rules alone.

## Folders

**Keep (defaults):** Priority (Inbox), People, Money, Work updates, Orders & travel, Reports & alerts, plus user-defined folders.

**Discard:** Marketing & newsletters and low-value notifications → `Discarded` (moved to Trash after 30 days); spam & phishing → Junk. Gmail: folders map to labels, and discard removes the Inbox label.

Moves use `MOVE` where the server supports it, otherwise COPY + `\Deleted` + `UID EXPUNGE`. An undo log is kept for 7 days.

## Screens

Cover screen: 01 Add account · 02 What matters to you · 03 First run: keep vs discard · 04 Inbox · 05 Discarded digest · 06 Reply editor · 07 Folders & rules.
Inner screen: 08 Portrait (list + email) · 09 Landscape (rail + list + email) · 10 Flex mode (email above hinge, reply below).

## Safety

- Nothing is sent without the user tapping Send on the full text.
- Email content is untrusted: it is passed to the model as data, never as instructions.
- No permanent deletes; discarded mail is recoverable for 30 days.

## Open points

- Play Store release with Gmail access requires Google's restricted-scope verification and a security assessment.
- Gemini Nano runs on recent flagships only; other devices fall back to rules (optional cloud model is undecided).
- Nano's context window is small: long threads are trimmed to the latest messages.
