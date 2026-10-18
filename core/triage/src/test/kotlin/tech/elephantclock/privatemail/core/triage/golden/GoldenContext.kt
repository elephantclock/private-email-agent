package tech.elephantclock.privatemail.core.triage.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.Protections
import tech.elephantclock.privatemail.core.triage.people.RelationshipIndex
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules
import tech.elephantclock.privatemail.core.triage.rules.RulesEngine
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor
import java.io.File

/**
 * The mailbox the golden fixtures are triaged for (`fixtures/context.json`): the user's own
 * addresses, who they have written to, their own Message-IDs, VIPs, learned rules and their
 * server's authserv-id. Frequent domains come from the relationship index, as in the app.
 */
class GoldenContext private constructor(
    val account: AccountId,
    val index: RelationshipIndex,
    val rules: List<TriageRule>,
    val trustedAuthservIds: Set<String>,
) {
    val engine: RulesEngine =
        RulesEngine(
            account = account,
            protections = Protections(index),
            extractor = SignalExtractor(index.frequentDomains(), trustedAuthservIds),
            learned = LearnedRules(rules),
        )

    /** Every fixture, sorted by file name, parsed with [EmlParser]. */
    fun fixtures(): List<Pair<String, MailItem>> =
        fixtureFiles().mapIndexed { i, f -> f.name to f.inputStream().use { EmlParser.parse(it, account, i + 1L) } }

    companion object {
        val dir: File = File(requireNotNull(GoldenContext::class.java.getResource("/fixtures")).toURI())

        fun fixtureFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(".eml") }.orEmpty().sortedBy { it.name }

        fun load(): GoldenContext {
            val root = Json.parseToJsonElement(File(dir, "context.json").readText()).jsonObject
            val account = AccountId(root.str("account"))
            val me = root.getValue("me").jsonArray.map { Address.of(it.jsonPrimitive.content) }
            val builder = RelationshipIndex.Builder(me)
            root.getValue("sent").jsonArray.map { it.jsonObject }.forEach {
                builder.addSentStats(it.str("email"), it.int("count"), it.getValue("lastSentMillis").jsonPrimitive.long)
            }
            root.getValue("ownMessageIds").jsonArray.forEach { builder.addOwnMessageId(it.jsonPrimitive.content) }
            root.getValue("vips").jsonArray.forEach { builder.addVip(it.jsonPrimitive.content) }
            val rules = root.getValue("learnedRules").jsonArray.map { rule(it.jsonObject) }
            val trusted =
                root
                    .getValue("trustedAuthservIds")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            return GoldenContext(account, builder.build(), rules, trusted)
        }

        private fun rule(o: JsonObject): TriageRule {
            val value = o.str("value")
            val target = FolderTarget(FolderRole.valueOf(o.str("target")), o["customName"]?.jsonPrimitive?.content)
            return TriageRule(
                id = o.getValue("id").jsonPrimitive.long,
                accountId = null,
                matcher = if (o.str("matcher") == "sender") RuleMatcher.Sender(value) else RuleMatcher.Domain(value),
                action = LearnedRules.actionFor(target),
                target = target,
                createdAtMillis = 1_760_000_000_000,
                vip = o["vip"]?.jsonPrimitive?.boolean ?: false,
            )
        }

        private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

        private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()
    }
}
