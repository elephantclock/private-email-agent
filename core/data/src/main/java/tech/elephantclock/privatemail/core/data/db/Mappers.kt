package tech.elephantclock.privatemail.core.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.mail.ops.UndoEntry
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Attachment
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.model.Verdict

private val json = Json { ignoreUnknownKeys = true }
private val addresses = ListSerializer(Address.serializer())
private val strings = ListSerializer(String.serializer())
private val headerMap = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
private val attachments = ListSerializer(Attachment.serializer())

fun Set<MessageFlag>.toBits(): Int = fold(0) { bits, f -> bits or (1 shl f.ordinal) }

fun Int.toFlags(): Set<MessageFlag> = MessageFlag.entries.filter { this and (1 shl it.ordinal) != 0 }.toSet()

private fun ServerConfig.columns() = ServerColumns(host, port, security)

private fun ServerColumns.config() = ServerConfig(host, port, security)

fun Account.toEntity(createdAtMillis: Long) =
    AccountEntity(
        id = id.value,
        email = email.email,
        emailName = email.name,
        displayName = displayName,
        username = username,
        imap = imap.columns(),
        smtp = smtp.columns(),
        authType = authType,
        providerId = providerId,
        createdAtMillis = createdAtMillis,
    )

fun AccountEntity.toModel() =
    Account(
        AccountId(id),
        Address(email, emailName),
        displayName,
        username,
        imap.config(),
        smtp.config(),
        authType,
        providerId,
    )

fun messageEntity(
    header: MessageHeader,
    body: MessageBody,
) = MessageEntity(
    accountId = header.ref.accountId.value,
    folderPath = header.ref.folderPath,
    uidValidity = header.ref.uidValidity,
    uid = header.ref.uid,
    messageIdHeader = header.messageId,
    fromEmail = header.from?.email,
    fromName = header.from?.name,
    subject = header.subject,
    sentAtMillis = header.sentAtMillis,
    receivedAtMillis = header.receivedAtMillis,
    toJson = json.encodeToString(addresses, header.to),
    ccJson = json.encodeToString(addresses, header.cc),
    replyToJson = json.encodeToString(addresses, header.replyTo),
    inReplyTo = header.inReplyTo,
    referencesJson = json.encodeToString(strings, header.references),
    flags = header.flags.toBits(),
    sizeBytes = header.sizeBytes,
    headersJson = json.encodeToString(headerMap, header.headers),
    snippet = body.textPreview,
    snippetTruncated = body.isTruncated,
    snippetMimeType = body.sourceMimeType,
    attachmentsJson = json.encodeToString(attachments, body.attachments),
    hasAttachments = body.attachments.any { !it.isInline },
)

fun MessageEntity.ref() = MessageRef(AccountId(accountId), folderPath, uidValidity, uid)

fun MessageEntity.toHeader() =
    MessageHeader(
        ref = ref(),
        messageId = messageIdHeader,
        from = fromEmail?.let { Address(it, fromName) },
        subject = subject,
        sentAtMillis = sentAtMillis,
        receivedAtMillis = receivedAtMillis,
        to = json.decodeFromString(addresses, toJson),
        cc = json.decodeFromString(addresses, ccJson),
        replyTo = json.decodeFromString(addresses, replyToJson),
        inReplyTo = inReplyTo,
        references = json.decodeFromString(strings, referencesJson),
        flags = flags.toFlags(),
        sizeBytes = sizeBytes,
        headers = json.decodeFromString(headerMap, headersJson),
    )

fun MessageEntity.toBody() =
    MessageBody(ref(), snippet, snippetTruncated, snippetMimeType, json.decodeFromString(attachments, attachmentsJson))

fun Verdict.toEntity(
    messageId: Long,
    decidedAtMillis: Long,
) = VerdictEntity(
    messageId = messageId,
    importance = importance,
    targetRole = target.role,
    targetCustomName = target.customName,
    action = action,
    source = source,
    reason = reason,
    confidence = confidence,
    summary = summary,
    decidedAtMillis = decidedAtMillis,
)

fun VerdictEntity.toModel() =
    Verdict(importance, FolderTarget(targetRole, targetCustomName), action, source, reason, confidence, summary)

fun TriageRule.toEntity() =
    LearnedRuleEntity(
        id = id,
        accountId = accountId?.value,
        matcherType = if (matcher is RuleMatcher.Sender) MatcherType.SENDER else MatcherType.DOMAIN,
        matcherValue =
            when (val m = matcher) {
                is RuleMatcher.Sender -> m.email
                is RuleMatcher.Domain -> m.domain
            },
        action = action,
        targetRole = target.role,
        targetCustomName = target.customName,
        vip = vip,
        createdAtMillis = createdAtMillis,
    )

fun LearnedRuleEntity.toModel() =
    TriageRule(
        id = id,
        accountId = accountId?.let(::AccountId),
        matcher =
            if (matcherType ==
                MatcherType.SENDER
            ) {
                RuleMatcher.Sender(matcherValue)
            } else {
                RuleMatcher.Domain(matcherValue)
            },
        action = action,
        target = FolderTarget(targetRole, targetCustomName),
        createdAtMillis = createdAtMillis,
        vip = vip,
    )

/** Decodes a stored address list (`toJson`, `ccJson`, ...). */
fun decodeAddresses(value: String): List<Address> = json.decodeFromString(addresses, value)

fun UndoEntry.toEntity() =
    UndoEntity(
        accountId = accountId.value,
        batchId = batchId,
        localId = localId,
        action = action,
        fromFolder = fromFolder,
        fromUidValidity = fromUidValidity,
        fromUid = fromUid,
        toFolder = toFolder,
        toUidValidity = toUidValidity,
        toUid = toUid,
        messageIdHeader = messageIdHeader,
        createdAtMillis = createdAtMillis,
    )

fun UndoEntity.toEntry() =
    UndoEntry(
        accountId = AccountId(accountId),
        batchId = batchId,
        localId = localId,
        action = action,
        fromFolder = fromFolder,
        fromUidValidity = fromUidValidity,
        fromUid = fromUid,
        toFolder = toFolder,
        toUidValidity = toUidValidity,
        toUid = toUid,
        messageIdHeader = messageIdHeader,
        createdAtMillis = createdAtMillis,
    )
