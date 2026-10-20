<img src="docs/brand/pea-logo.svg" alt="PEA: Private Email Agent" width="360">

# PEA: Private Email Agent

**An on-device AI email agent for Android. Keep what matters, discard the rest.**

PEA (Private Email Agent) classifies, summarises and drafts replies to your email on your phone. It runs on the phone's own AI chip, so your email isn't sent to an AI server. It sorts valuable mail into folders, holds marketing and low-value mail for review before it's binned, and only interrupts you for email that needs you.

> **Status:** design / pre-alpha. The features below describe the target app; nothing is downloadable yet.
>
> 🌐 **Website:** https://elephantclock.github.io/private-email-agent/ · **Early access:** [request here](https://github.com/elephantclock/private-email-agent/issues/new?template=early-access.yml)

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

- **Google and Microsoft:** OAuth sign-in (IMAP with XOAUTH2). No password stored.
- **Any other provider:** IMAP/SMTP with automatic server discovery.
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
- **On-device AI:** Gemini Nano through the ML Kit GenAI APIs (Prompt, Summarization, Rewriting, Proofreading), running in Android's AICore system service on the phone's NPU. ML Kit Language ID and Translation for other languages.
- **Rules first, AI second:** fast header-based rules handle most mail instantly; the AI only sees what rules can't decide.
- **Efficient sync:** IMAP IDLE while open, WorkManager in the background; headers plus a small slice of each body; AI results cached per message.
- **Storage:** Room with full-text search. Passwords encrypted with Android Keystore.

Gemini Nano is available on recent flagship phones only (for example the Galaxy Z Fold7 and Galaxy S25 series). Other phones get rules-based sorting.

## Privacy

- Your email is processed on the phone. There's no PEA server.
- The AI model has no tools: it can only return a label, a summary or a draft. Instructions inside emails are treated as text, never followed.
- Nothing is deleted outright; discarded mail is held for 30 days.

## Roadmap

1. IMAP sync, local storage, inbox UI, rules engine
2. On-device classification, summaries and notifications
3. Suggested replies with edit, rewrite and translation
4. First-run 30-day clean-up and folder moves
5. Foldable layouts, performance and battery tuning
6. Google and Microsoft OAuth, multiple accounts

See [docs/DESIGN.md](docs/DESIGN.md) for the design plan.

## License

[PolyForm Noncommercial 1.0.0](LICENSE). For commercial use, contact [Elephant Clock Technology](https://elephantclock.tech).
