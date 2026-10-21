<picture>
  <source srcset="docs/brand/pea-logo-dark.svg" media="(prefers-color-scheme: dark)">
  <img src="docs/brand/pea-logo.svg" alt="PEA: Private Email Agent" width="360">
</picture>

# PEA: Private Email Agent

**An on-device AI email agent for Android. Keep what matters, discard the rest.**

PEA (Private Email Agent) classifies, summarises and drafts replies to your email on your phone. It runs on the phone's own AI chip, so your email isn't sent to an AI server. It sorts valuable mail into folders, holds marketing and low-value mail for review before it's binned, and only interrupts you for email that needs you.

> **Status:** pre-alpha (0.1.0). I built PEA for my own work mail at izi.TRAVEL and use it daily on a Galaxy Z Fold7. [Read the story](https://elephantclock.github.io/private-email-agent/#story).
>
> ⬇️ **Download:** [PEA-0.1.0-prealpha.apk](https://github.com/elephantclock/private-email-agent/releases/download/v0.1.0-prealpha/PEA-0.1.0-prealpha.apk) (Android 12+, debug build; [release notes](https://github.com/elephantclock/private-email-agent/releases/tag/v0.1.0-prealpha)) · 🌐 **Website:** https://elephantclock.github.io/private-email-agent/ · **Early access to the signed beta:** [request here](https://github.com/elephantclock/private-email-agent/issues/new?template=early-access.yml)

## Supported devices

PEA runs on any Android 12+ phone. On-device AI depends on Google's Gemini Nano support for your model:

| Devices | What you get |
|---|---|
| Galaxy Z Fold7, Z TriFold, S26 / S26+ / S26 Ultra, Z Flip8, Z Fold8, Z Fold8 Ultra | Everything: AI sorting, thread summaries, reply drafts, rewriting, proofreading and translation |
| Galaxy S25 / S25+ / S25 Ultra | Rules only for now (these phones have Google's smaller summarising/rewriting/proofreading models; support is planned) |
| Every other Android 12+ phone | The full app with rules-based sorting; AI buttons hidden |

Tested so far on a Galaxy Z Fold7. Gemini Nano only runs while the app is on screen, so background sync always uses rules.

---

## What it does

| | |
|---|---|
| **Classify** | Every email gets an importance level (urgent, high, normal, low) and a folder. |
| **Summarise** | A short brief per thread: what it's about, who it's waiting on, deadline, next steps. |
| **Reply** | A suggested reply in the sender's language and your tone. Edit it, rewrite it shorter or more formal, then send. Nothing is sent without your tap. |
| **First-run clean-up** | After you sign in, it sorts the last 30 days and shows a keep / discard review before anything moves. |
| **Ongoing triage** | New mail is sorted as it arrives. Notifications only for urgent and high-priority mail. |
| **Translate** | Mail in other languages is shown in yours, offline. |

## Keep or discard

**Kept, in value folders** (defaults, all editable):
Priority (stays in Inbox) · People · Money · Work updates · Orders & travel · Reports & alerts · plus your own folders, described in plain words ("Partners: anyone asking to integrate or resell our product").

**Discarded:**
- Marketing, newsletters and low-value notifications go to a **Discarded** folder and are moved to Trash after 30 days.
- Spam and phishing go to the server's Junk folder.
- A daily digest lists what was discarded, with Restore, Never discard and Unsubscribe.

**Never discarded:** people you've written to, your contacts, threads you're in, VIPs, security codes, and anything about money.

## Works with any email account

- **Any IMAP/SMTP provider:** company and hosting mail, with server settings found automatically (or entered by hand).
- **Gmail and Google Workspace:** works today with an [app password](https://support.google.com/accounts/answer/185833).
- **Google and Microsoft sign-in (OAuth):** planned. Until then most Microsoft 365 and Outlook.com accounts won't connect, because they block password sign-in.
- Rules are general, not tied to one mailbox. They use the signals every email carries (mailing-list and bulk headers, SPF/DKIM/DMARC results, whether you know the sender), then on-device AI sorts the rest using your folder descriptions.
- Every correction you make becomes a rule.

## Built for foldables

Designed first for the **Samsung Galaxy Z Fold7**, and adapts to any Android phone or tablet:

| Screen | Layout |
|---|---|
| Cover screen / phone | One pane, bottom navigation |
| Inner screen, portrait | Email list + open email |
| Inner screen, landscape | Folder rail + list + open email |
| Half-folded (Flex mode) | Email above the hinge, reply below |

## How it works

- **Kotlin + Jetpack Compose**, Material 3 Adaptive layouts, Jetpack WindowManager for fold posture.
- **On-device AI:** Gemini Nano through the ML Kit GenAI APIs (Prompt, Summarization, Rewriting, Proofreading), running in Android's AICore system service on the phone's NPU. Language detection from Google Play services; Gemini Nano also translates, so there are no extra models to download.
- **Rules first, AI second:** fast header-based rules handle most mail instantly; the AI only sees what rules can't decide.
- **Efficient sync:** IMAP IDLE while open, WorkManager in the background; headers plus a small slice of each body. AI runs only while the app is on screen, and results are cached per message.
- **Storage:** Room with full-text search. Passwords encrypted with Android Keystore.

Which phones get the AI: see [Supported devices](#supported-devices).

## Privacy

- Your email is processed on the phone. There's no PEA server.
- The AI model has no tools: it can only return a label, a summary or a draft. Instructions inside emails are treated as text, never followed.
- Nothing is deleted outright; discarded mail is held for 30 days.

## Roadmap

1. **Done.** Mail sync, local storage, inbox and rules engine
2. **Done.** On-device classification, summaries and alerts
3. **Done.** Suggested replies with rewrite, proofreading and translation
4. **Done.** First-run 30-day clean-up, with folder moves off until you turn them on
5. **Done.** Foldable layouts and multiple accounts
6. **In progress.** AI evaluation on real mail, half-folded layout check on the Fold7, performance, battery, accessibility and a security review, then a signed beta
7. **Later.** Google and Microsoft sign-in

The task-by-task plan and its log are in [docs/DEV_PLAN.md](docs/DEV_PLAN.md); the design is in [docs/DESIGN.md](docs/DESIGN.md).

## Build from source

You need JDK 21 and the Android SDK (Android Studio ships both).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew spotlessCheck lint unitTest :app:verifyRoborazziDebug   # what CI runs
```

| Module | What's in it |
|---|---|
| `:app` | Compose UI, adaptive and Flex layouts, workers, notifications, wiring |
| `:core:model` | Domain types (messages, verdicts, folders, rules) |
| `:core:triage` | Signals, protections and the rules engine, tested against a corpus of sample emails |
| `:core:mail` | IMAP/SMTP (Angus Mail), server discovery, sync, IDLE, send and move |
| `:core:data` | Room database with full-text search, the triage orchestrator, settings, Keystore-encrypted secrets |
| `:core:ai` | ML Kit GenAI clients, prompts, safety checks, the AI work queue |

## License

[PolyForm Noncommercial 1.0.0](LICENSE). For commercial use, contact [Elephant Clock Technology](https://elephantclock.tech).
