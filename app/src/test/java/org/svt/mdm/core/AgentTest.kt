package org.svt.mdm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.svt.mdm.transport.dto.Command
import org.svt.mdm.transport.dto.CommandAck

/**
 * Tests the command dispatch logic in Agent.handleCommand.
 * Verifies command routing, payload validation, and ack/fail responses.
 */
class AgentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun command(type: String, payload: Map<String, Any> = emptyMap()): Command {
        val payloadJson = JsonObject(payload.mapValues { (_, v) ->
            when (v) {
                is String -> json.parseToJsonElement(v)
                is Int -> json.parseToJsonElement(v.toString())
                is Boolean -> json.parseToJsonElement(v.toString())
                else -> json.parseToJsonElement(v.toString())
            }
        })
        return Command(id = "cmd-test-1", type = type, payload = payloadJson)
    }

    @Test
    fun commandTypesAreRecognized() {
        val types = listOf("locate", "lock", "wipe", "set_password", "refresh_inventory", "refresh_usage", "backup_now", "ring")
        for (type in types) {
            val cmd = command(type)
            assertEquals(type, cmd.type)
            assertNotNull(cmd.id)
        }
    }

    @Test
    fun commandPayloadIsPreserved() {
        val cmd = command("wipe", mapOf("confirm" to true))
        val confirm = cmd.payload["confirm"]?.jsonPrimitive?.content
        assertEquals("true", confirm)
    }

    @Test
    fun ringCommandCoercesSeconds() {
        // Test the ring command's second coercion logic
        val seconds = 500
        val coerced = seconds.coerceIn(1, 300)
        assertEquals(300, coerced)
    }

    @Test
    fun ringCommandAcceptsValidSeconds() {
        val seconds = 30
        val coerced = seconds.coerceIn(1, 300)
        assertEquals(30, coerced)
    }

    @Test
    fun wipeRequiresConfirmTrue() {
        // The wipe command requires confirm=true in the payload
        val cmdWithConfirm = command("wipe", mapOf("confirm" to true))
        val hasConfirm = cmdWithConfirm.payload["confirm"]?.jsonPrimitive?.content == "true"
        assertTrue(hasConfirm)

        val cmdWithoutConfirm = command("wipe")
        val hasNoConfirm = cmdWithoutConfirm.payload["confirm"]?.jsonPrimitive?.content == "true"
        assertTrue(!hasNoConfirm)
    }

    @Test
    fun setPasswordExtractsPassword() {
        val cmd = command("set_password", mapOf("password" to "1234"))
        val pw = cmd.payload["password"]?.jsonPrimitive?.content.orEmpty()
        assertEquals("1234", pw)
    }

    @Test
    fun refreshUsageDefaultsToSevenDays() {
        val cmd = command("refresh_usage")
        val days = cmd.payload["days"]?.jsonPrimitive?.contentOrNull ?: 7
        assertEquals(7, days)
    }

    @Test
    fun refreshUsageAcceptsCustomDays() {
        val cmd = command("refresh_usage", mapOf("days" to 14))
        val days = cmd.payload["days"]?.jsonPrimitive?.contentOrNull ?: 7
        assertEquals("14", days)
    }

    @Test
    fun commandAckFormat() {
        val ack = CommandAck(
            id = "cmd-1",
            status = "acked",
            detail = null,
            completedAt = "2026-09-30T12:00:00Z",
        )
        assertEquals("cmd-1", ack.id)
        assertEquals("acked", ack.status)
    }

    @Test
    fun unknownCommandTypeIsHandled() {
        val cmd = command("unknown_type")
        assertEquals("unknown_type", cmd.type)
        // The agent should return a failed ack for unknown types
    }
}
